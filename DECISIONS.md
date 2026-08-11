# DECISIONS.md

Registro de decisiones técnicas del portal de retiros. Está escrito para ser leído junto al
código: cada afirmación apunta a una clase, un método o una query concreta, y cada mecanismo
viene con las alternativas que se descartaron y por qué.

El README cubre cómo levantar y correr el proyecto. Este documento cubre **por qué está hecho
así**.

---

## 0. Estado de implementación

El documento describe únicamente lo que el código hace hoy. Lo que todavía no está
implementado tiene su sección marcada como pendiente en vez de omitirse, para que la
estructura de lectura sea la misma cuando se complete.

| Pieza | Estado |
|---|---|
| Modelo de datos + migraciones Flyway | Implementado |
| Mocks de riesgo y banco (con semántica de idempotencia) | Implementado |
| Reserva atómica de saldo (C5) | Implementado + test de concurrencia |
| Creación de retiro (`POST /api/withdrawals`) | Implementado |
| Poller de evaluación de riesgo (C2 / C3) | Implementado + test de concurrencia |
| Autorizar / rechazar / reintentar / resolver revisión manual, con locking optimista (C4) | Implementado + test de concurrencia |
| Poller de ejecución de transferencia (C1) | Implementado + tests de los 4 desenlaces del banco |
| Poller de reconciliación post-timeout (C6) | Implementado + tests de los 2 timeouts (aplicado / no aplicado) y del escalamiento a `MANUAL_REVIEW` con salida por operador |
| Listado con filtros (`GET /api/withdrawals`) + detalle + cuentas | Implementado |
| Frontend de backoffice (grilla, filtros, detalle con historial, autorizar/rechazar/reintentar/resolver, alta de retiro, polling, manejo de 409) | Implementado y verificado en navegador contra el stack real |
| docker-compose multi-instancia | Implementado y validado end-to-end (`backend-1`/`backend-2`/`frontend` + Postgres) |

Los estados `PROCESSING_TRANSFER`, `EXECUTED`, `FINAL_ERROR`, `RETRYABLE_ERROR` y
`MANUAL_REVIEW` del enum `WithdrawalStatus`, y la tabla `transfer` completa, ya existen en el
modelo porque el diseño de la máquina de estados se cerró antes de escribir código.

---

## 1. Cómo correr el proyecto

Las instrucciones detalladas están en `README.md`. En resumen: `docker compose up -d` levanta
Postgres, Flyway aplica migraciones y seed al arrancar el backend (`backend/gradlew bootRun`),
y `backend/gradlew test` corre la suite —requiere Docker corriendo, porque los tests de
integración usan Testcontainers con un Postgres real (ver sección 11).

---

## 2. Modelo de datos

Cuatro tablas, definidas en `backend/src/main/resources/db/migration/V1__init.sql`.

### 2.1 `account`

```sql
balance          numeric(18,2) not null check (balance >= 0),
reserved_balance numeric(18,2) not null default 0 check (reserved_balance >= 0),
constraint chk_account_reserved_within_balance check (balance >= reserved_balance)
```

Dos columnas de saldo en vez de una. El saldo disponible es una **derivada**
(`balance - reserved_balance`), no una columna: un retiro vivo compromete dinero pero todavía
no lo debita — el débito real recién ocurre cuando el banco confirma la transferencia. Esto es
lo que hace que C5 se resuelva con un solo `UPDATE` condicional sobre una fila (sección 7).

*Alternativa descartada*: no guardar reserva y calcular el disponible como
`balance - SUM(amount)` de los retiros vivos en cada creación. Es correcto pero requiere una
agregación sobre un rango de filas dentro de la sección crítica; para que sea segura con 2+
instancias haría falta `SERIALIZABLE` o un lock de rango, contra un único `UPDATE` sobre una
fila que Postgres ya serializa gratis.

La entidad `Account` (`domain/Account.java`) es deliberadamente **de solo lectura**: tiene
getters y un constructor, no tiene setters de `balance`/`reservedBalance`. Los saldos nunca se
mutan a través del entity manager, solo a través de los tres UPDATE atómicos de
`AccountRepository`. Un setter ahí sería una puerta abierta a un read-modify-write que rompe
C5 sin que nada avise.

`account` tampoco tiene columna `version`: los escritores concurrentes ya pasan por UPDATE
condicionales que Postgres serializa a nivel de fila. Un `@Version` sin usar sería peso muerto
y además invitaría a mutar la entidad, que es justo lo que se quiere evitar.

### 2.2 `withdrawal`

La raíz del agregado. Campos relevantes:

- `status` — máquina de estados, ver 2.5.
- `risk_level` / `risk_evaluated_at` — resultado de la evaluación asíncrona; `risk_level` queda
  `null` si el servicio de riesgo falló (el fail-safe de C2 no inventa un nivel).
- `idempotency_key uuid not null unique` — se genera **una vez por retiro** en
  `WithdrawalService.createWithdrawal` y es `updatable = false` en la entidad. Es la clave de
  negocio de C6: se reutiliza en todos los reintentos contra el banco, así que un retiro nunca
  puede generar dos transferencias reales. No es una idempotency key de request HTTP.
- `transfer_id` — FK a `transfer` (agregada al final de la migración porque las dos tablas se
  referencian mutuamente).
- `updated_by` — quién hizo la última transición (`CLIENT`, `SYSTEM_RISK`, o el id de
  operador). Es lo que el 409 de C4 le devuelve al operador que pierde la carrera.
- `version bigint not null default 0` — `@Version` de JPA, el mecanismo de C4 (sección 6).

Índices: `(status, created_at)` sirve exactamente al patrón de los pollers
(`WHERE status = ? ORDER BY created_at LIMIT n`), `account_id` y `destination_cbu` sirven al
listado con filtros.

### 2.3 `transfer`

Tabla separada, relación 1:1 con `withdrawal` (`withdrawal_id ... unique`). La separación es
deliberada: `withdrawal.status` es estado **de negocio** (¿este retiro está aprobado?),
`transfer.status` es estado **de integración** (¿qué contestó el banco?). Mezclarlos en una
sola tabla obliga a que el retiro cargue con `attempt_count`, `requested_at`, `bank_reference`,
`last_error` y `reconciliation_attempts`, que son el ciclo de vida de los intentos contra un
sistema externo y no del retiro en sí.

El 1:1 con `attempt_count` (en vez de una fila por intento) es una decisión consciente:
*muchos intentos, una sola transferencia real*. La fila `transfer` representa la transferencia
única identificada por `idempotency_key`, no cada llamada HTTP al banco. La traza de qué pasó
en cada paso vive en `withdrawal_status_history`.

`TransferStatus`: `PENDING`, `SUCCEEDED`, `FAILED_INVALID_ACCOUNT`, `FAILED_INTERNAL_ERROR`,
`AWAITING_RECONCILIATION`. El último es el punto importante del diseño de C6: el timeout no se
modela como éxito ni como fallo, sino como un tercer estado explícito que un job de
reconciliación tiene que resolver contra el banco.

### 2.4 `withdrawal_status_history`

Append-only: `(withdrawal_id, previous_status, new_status, actor, occurred_at)`. Sin setters en
la entidad, todas las columnas `updatable = false`.

**No estaba pedida en el enunciado.** Se agregó porque el dominio es un broker regulado por
CNV: `withdrawal.updated_by` solo dice quién tocó el retiro por última vez, y eso no permite
responder la pregunta que un área de compliance hace de verdad — "mostrame la secuencia
completa de decisiones sobre este retiro: quién lo creó, qué dijo el motor de riesgo, quién lo
autorizó, cuántos intentos hubo contra el banco y cuándo". Reconstruir eso desde los logs de la
aplicación no es una respuesta aceptable en un dominio regulado: los logs rotan y no son
transaccionales con el cambio de estado.

El costo es bajo y acotado (un insert por transición, en la misma transacción que la
transición, vía `WithdrawalService.recordTransition` y el equivalente en `TransferOutcomeService`
y `RiskEvaluationPoller`), así que entra dentro del criterio de "no agregar features no pedidas":
no es una feature de producto, es la tabla que el dominio exige.

`GET /api/withdrawals/{id}` la expone: `WithdrawalDetailResponse.history` es una lista de
`HistoryEntry` (`previousStatus`, `newStatus`, `actor`, `occurredAt`), leída con
`WithdrawalStatusHistoryRepository.findByWithdrawalIdOrderByOccurredAt` y ordenada de más viejo a
más nuevo. Sin esto la tabla existía pero no había forma de que un operador o compliance la
consultaran sin ir directo a la base — la trazabilidad quedaba en el modelo, no en el producto.

*Alternativa descartada*: Hibernate Envers. Da versionado automático de toda la entidad, pero
audita cambios de campos, no decisiones de negocio, y agrega una dependencia y un esquema
paralelo para obtener menos información de la que dan cinco columnas escritas a mano.

### 2.5 Máquina de estados (`WithdrawalStatus`)

Nombres reales del enum en `domain/WithdrawalStatus.java`:

```
                        (POST /api/withdrawals — reserva de saldo + insert)
                                         │
                                  EVALUATING_RISK
                                         │  RiskEvaluationPoller
                     ┌───────────────────┴────────────────────┐
            riesgo LOW/MEDIUM                    riesgo HIGH o fallo del servicio
                     │                                        │
                 AUTHORIZED ◄──── authorize() ───── PENDING_AUTHORIZATION
                     │                                        │
                     │                                   reject()
                     │                                        ▼
                     │                                    REJECTED  (libera reserva)
                     │
                     │  TransferExecutionPoller (fase A)
                     ▼
             PROCESSING_TRANSFER
                     │
     ┌───────────────┼────────────────┬─────────────────────┐
     ▼               ▼                ▼                     ▼
 EXECUTED     FINAL_ERROR      RETRYABLE_ERROR        MANUAL_REVIEW
 (settle)   (libera reserva)   (reintento manual)  (reconciliación agotada)
                     ▲                                       │
                     └──────── resolveManualReview() ────────┘
                                (libera reserva)
```

Todas las transiciones del diagrama están implementadas: `EVALUATING_RISK` → `RiskEvaluationPoller`;
`PENDING_AUTHORIZATION` → `WithdrawalService.authorize`/`reject`; `AUTHORIZED` →
`PROCESSING_TRANSFER` → `TransferExecutionPoller` (fases A/B/C, sección 3); y desde
`PROCESSING_TRANSFER` hacia `EXECUTED`/`FINAL_ERROR`/`RETRYABLE_ERROR`/de vuelta a `AUTHORIZED`/
`MANUAL_REVIEW` → `TransferOutcomeService` + `ReconciliationPoller` (sección 8). `MANUAL_REVIEW`
no es un final sin salida: `POST /{id}/resolve-manual-review` → `WithdrawalService.resolveManualReview`
lo cierra a `FINAL_ERROR` liberando la reserva (sección 8).

Dos decisiones de la máquina de estados que vale la pena defender:

1. **`PROCESSING_TRANSFER` es un estado explícito**, no un flag. Sin él no hay forma de
   distinguir "autorizado y esperando que alguien lo tome" de "hay una llamada al banco en
   vuelo ahora mismo", y esa distinción es exactamente lo que impide que dos instancias tomen
   el mismo retiro (C3) y lo que le da a la reconciliación algo concreto que buscar (C6).
2. **`RETRYABLE_ERROR` y `FINAL_ERROR` son estados distintos** porque el banco distingue dos
   fallos con semántica opuesta: cuenta inválida es terminal (reintentar es inútil y además
   está cacheado del lado del banco), error interno es transitorio (reintentar es lo correcto).
   Colapsarlos en un solo `FAILED` obligaría al operador a adivinar cuál de los dos es.

---

## 3. C1 — La llamada lenta al banco no bloquea al operador

**Mecanismo**: `poller/TransferExecutionPoller`, partido en tres fases.

El punto de partida es arquitectónico, no un truco de latencia: `WithdrawalService` **no tiene
ninguna referencia a `BankService`**. `POST /{id}/authorize` cambia el estado a `AUTHORIZED`,
escribe la fila de historia y responde, en el orden de milisegundos. La transferencia la ejecuta
después un poller, en otro hilo y en otra transacción. Un operador no puede quedar esperando al
banco porque no existe un camino de código desde el endpoint hasta el banco. El contrato lo dice
en el javadoc de `BankService.executeTransfer`: *"never call this from a request thread or while
holding a DB row lock (C1)"*.

**Fase A — claim** (`claimBatch()`, transacción corta `REQUIRES_NEW`). `lockNextBatchForTransfer`
toma hasta `batch-size` (5) retiros en `AUTHORIZED` con `FOR UPDATE SKIP LOCKED`; para cada uno
crea —o reutiliza, si es un reintento— la fila `transfer` con la idempotency key del retiro,
incrementa `attempt_count`, sella `requested_at`, mueve el retiro a `PROCESSING_TRANSFER`, escribe
la historia y commitea. El lock de fila dura exactamente eso: milisegundos. El método devuelve
solo la lista de ids, no entidades, justamente porque lo que sigue ya no está en esa transacción.

**Fase B — la llamada al banco** (`processOne`, despachado en `transferExecutionExecutor`, la
llamada real al banco corriendo en `bankCallExecutor`, **sin transacción y sin lock**). Es la
fase de 3-10s. Está fuera de toda transacción a propósito: sostenerla adentro significaría
mantener el row lock durante toda la llamada (justo lo que C1 y C3 evitan) y tener una conexión
de Hikari tomada por cada transferencia en vuelo sin hacer nada con ella.

**Fase C — aplicar el resultado** (`TransferOutcomeService`). Cada desenlace se escribe en su
propia transacción corta `REQUIRES_NEW`, guardada por el UPDATE condicional de C6 (sección 8).

Por qué separadas así y no en una sola transacción como el poller de riesgo (sección 4): la fase
A es un **compromiso persistido antes de la llamada externa**. Si el proceso muere durante la
fase B, el retiro ya quedó en `PROCESSING_TRANSFER` con su `requested_at`, y la reconciliación lo
encuentra. Si claim y llamada compartieran transacción, un rollback devolvería el retiro a
`AUTHORIZED` y el próximo tick lo reintentaría **sin saber si el banco ya lo aplicó**, que es
literalmente cómo se produce una transferencia doble. C1 y C6 no son dos diseños distintos: el
split de fases resuelve los dos.

**Dos pools dedicados, no uno** (`config/TransferExecutorConfig`):

| Pool | Core | Max | Cola | Prefijo de hilo | Qué corre ahí |
|---|---|---|---|---|---|
| `transferExecutionExecutor` | 10 | 15 | 50 | `transfer-dispatch-` | `processOne`: las dos lecturas de antes, el `join()` bloqueado esperando la fase B, y la fase C |
| `bankCallExecutor` | 6 | 8 | 20 | `bank-call-` | El único punto del código que llama `BankService.executeTransfer` |

Por qué dos y no uno: el hilo que despacha (`transferExecutionExecutor`) pasa casi todo su tiempo
bloqueado en `future.join()`, así que es barato tenerlo de sobra; el hilo que efectivamente llama
al banco (`bankCallExecutor`) es el techo real de concurrencia contra el proveedor externo y tiene
que quedar acotado aparte. Si la llamada al banco se despachara *de vuelta* sobre el mismo pool
que la dispara, el sistema se auto-mata de hambre: en el peor caso los 15 hilos del dispatcher
están todos bloqueados esperando un turno en `bankCallExecutor`, que nunca se libera porque nada
más los dispara. Son dos responsabilidades con dos ritmos distintos —despachar es instantáneo,
llamar al banco tarda 3-10s— y cada una necesita su propio techo.

Tres decisiones más, dentro de eso:

- **Ninguno de los dos es el pool de scheduling.** Si la fase B corriera ahí, ocho llamadas de
  10s vaciarían los 4 hilos de `spring.task.scheduling.pool.size` y frenarían también al poller
  de riesgo y al de reconciliación. El scheduler tiene que quedar libre para seguir tomando
  lotes; es un pool para disparar trabajo, no para esperarlo.
- **Ninguno es un executor sin cota.** Un pool ilimitado bajo un burst de autorizaciones es cómo
  se termina con un número descontrolado de llamadas concurrentes contra el banco. 8 hilos en
  `bankCallExecutor` son un techo explícito de concurrencia contra el proveedor externo.
- **El dimensionamiento de `hikari.maximum-pool-size: 15` está atado a `bankCallExecutor` (8),
  no al dispatcher.** El dispatcher toca la base solo al principio (dos lecturas) y al final (la
  fase C), nunca durante el `join()`, así que aunque tenga hasta 15 hilos no retiene 15
  conexiones a la vez. `bankCallExecutor` es el que fija el piso de conexiones que hacen falta
  aparte del tráfico HTTP normal y los hilos de scheduling.

**Timeout duro del lado del cliente** (`callBankWithTimeout`): el mock tarda 3-10s, pero un banco
real puede no contestar nunca, y un worker de `bankCallExecutor` bloqueado para siempre es un
worker menos hasta el próximo reinicio.

```java
CompletableFuture.supplyAsync(
        () -> bankService.executeTransfer(...),
        bankCallExecutor.getThreadPoolExecutor()
).orTimeout(bankCallTimeoutSeconds, TimeUnit.SECONDS);
```

El executor explícito como segundo argumento no es cosmético: la variante de un solo argumento
de `supplyAsync` corre sobre `ForkJoinPool.commonPool()`, que no es ninguno de los dos pools que
este código controla — en un contenedor de pocos CPUs ese pool puede tener paralelismo 1 (las
llamadas se serializan detrás del timeout) o directamente no existir como tal, cayendo en un
executor sin cota por tarea, que es exactamente lo que un pool dedicado y acotado existe para
evitar.

`bank-call-timeout-seconds: 15`. Las excepciones chequeadas del banco se envuelven en
`CompletionException` dentro del supplier y se desenvuelven en el `catch` de `join()`, para que
cada tipo de fallo llegue a su rama en vez de colapsar todo en un error genérico. El
`TimeoutException` propio se trata igual que el `BankTimeoutException` que reporta el banco: en
los dos casos el sistema **no sabe** qué pasó, así que ninguno se resuelve como éxito ni como
fallo. Sin esta cota tampoco habría con qué dimensionar el grace period de la reconciliación
(30s > 15s, sección 8).

**Detalle honesto**: el timeout de cliente no cancela la llamada en vuelo sobre `bankCallExecutor`
— Java no puede interrumpir una llamada bloqueante sin cooperación del lado que bloquea. El hilo
sigue ocupando un slot de `bankCallExecutor` hasta que la llamada real vuelve, aunque el poller ya
haya seguido adelante tratándola como timeout. Por eso el techo real de concurrencia contra el
banco en el peor caso es el tamaño de `bankCallExecutor` (8) y no el timeout: el timeout acota
cuánto tarda el poller en *enterarse*, no cuántas llamadas siguen realmente en curso.

Cada desenlace de la fase B tiene su rama explícita:

| Resultado de la fase B | Fase C | Estado del retiro | Reserva |
|---|---|---|---|
| Éxito | `applySuccess` | `EXECUTED` | se debita (`settle`) |
| `InvalidAccountException` | `applyInvalidAccount` | `FINAL_ERROR` | se libera |
| `InternalErrorException` | `applyInternalError` | `RETRYABLE_ERROR` | se mantiene: el reintento la necesita |
| `BankTimeoutException` o el `TimeoutException` propio | `markAwaitingReconciliation` | sigue en `PROCESSING_TRANSFER` | se mantiene |
| `IdempotentRequestInProgressException` | ninguna, se loguea | sigue en `PROCESSING_TRANSFER` | se mantiene |

El último caso no debería ocurrir —`SKIP LOCKED` garantiza un solo worker por retiro— y
justamente por eso no se lo intenta resolver adivinando: se loguea y se deja que la reconciliación
lo levante después del grace period. Un caso "imposible" que igual se auto-resuelve es mejor que
un caso "imposible" que escribe un estado inventado.

**Detalle que sostiene todo esto**: `tick()` llama a `self.claimBatch()` a través de una
auto-referencia `@Lazy`, no `this.claimBatch()`. Una auto-invocación directa se saltea el proxy de
Spring y **descarta silenciosamente** el `@Transactional(REQUIRES_NEW)`, con lo cual el claim
correría sin transacción propia y sin el lock que lo hace seguro. No es teórico: el bug estaba en
`RiskEvaluationPoller` y los tests no lo veían porque invocan el bean inyectado, que sí pasa por
el proxy. Se corrigió en los tres pollers en el mismo commit.

**Verificación**: `TransferExecutionPollerTest`, cuatro escenarios end-to-end contra Postgres real
y el poller real (no un servicio mockeado):

- `successfulTransferSettlesBalanceAndMarksExecuted` — `EXECUTED`, balance 100.000 → 99.900,
  `reserved_balance` en 0, `transfer` en `SUCCEEDED` con `bankReference`, y el `transfer_id`
  apuntado desde el retiro.
- `invalidAccountEndsInFinalErrorAndReleasesReservedBalance` — `FINAL_ERROR`, reserva liberada y
  balance real **intacto**: no se movió plata, solo se soltó lo comprometido.
- `internalErrorEndsInRetryableErrorAndKeepsReservedBalance` — `RETRYABLE_ERROR` con la reserva
  **todavía tomada**, porque el reintento del operador (`POST /{id}/retry`) la necesita.
- `timeoutLeavesWithdrawalProcessingAndMarksTransferAwaitingReconciliation` — el retiro queda en
  `PROCESSING_TRANSFER` y el `transfer` en `AWAITING_RECONCILIATION`. Nada se auto-resuelve.

Que estos tests pasen es además la prueba indirecta de que la fase B corre fuera de la transacción
del claim: si no fuera así, la fase C —que es `REQUIRES_NEW`, o sea otra conexión— intentaría un
UPDATE sobre una fila que la transacción externa todavía tiene lockeada, y el test se colgaría
esperando en vez de pasar.

**Alternativas descartadas**:

- *Llamar al banco en el request, aunque sea con un timeout corto*: es la alternativa que hay que
  descartar con argumento porque suena razonable. No alcanza. Con un timeout de 2s el hilo del
  contenedor y su conexión quedan tomados esos 2s por cada operador igual, y lo que satura Tomcat
  bajo un burst de autorizaciones es la ocupación, no la lentitud en sí. Peor: si el proceso se
  reinicia con la llamada en vuelo, no queda nada que continúe el trabajo — el retiro quedó
  `AUTHORIZED` en la base y nadie lo va a retomar, o hay una transferencia aplicada en el banco de
  la que el sistema no tiene registro. El poller sobrevive al reinicio porque el trabajo pendiente
  está en una tabla, no en un hilo. Y el timeout corto tampoco elimina la ambigüedad: sigue
  habiendo un caso en el que no se sabe si el banco aplicó, solo que ahora ocurre en el hilo HTTP
  y con un operador mirando la pantalla.
- *`@Async` disparado desde el endpoint*: responde rápido, pero la unidad de trabajo vive en la
  cola en memoria del executor. Un reinicio la pierde y con 2 instancias no hay coordinación
  alguna — mismo argumento que en C2 (sección 4), y acá el trabajo perdido mueve plata.
- *Las tres fases en una sola transacción, como sí hace el poller de riesgo*: aceptable cuando el
  trabajo externo es una lectura sin efecto y un rollback es gratis. Acá sostendría el row lock
  10s y, sobre todo, un rollback dejaría el retiro en `AUTHORIZED` después de que el banco pudo
  haber movido la plata.
- *WebClient reactivo en vez de un pool de hilos*: escalaría mejor con miles de transferencias
  concurrentes, pero el cuello acá es el banco, no el hilo bloqueado, y meter un modelo reactivo
  en una aplicación que por lo demás es JDBC bloqueante (JPA, `SKIP LOCKED`, transacciones) es
  pagar la complejidad de dos modelos de programación sin ganar nada al volumen del problema.

---

## 4. C2 — El riesgo se evalúa una sola vez, de forma asíncrona

**Mecanismo**: `poller/RiskEvaluationPoller`.

`WithdrawalService.createWithdrawal` deja el retiro en `EVALUATING_RISK` y responde; nunca
llama a `RiskService`. El poller corre con
`@Scheduled(fixedDelayString = "${withdrawals.poller.risk-evaluation.fixed-delay-ms}")`
(1000 ms) y toma lotes de 5 (`batch-size`).

"Una sola vez" sale de que el claim y la escritura del resultado ocurren en **la misma
transacción**: `processBatch()` es `@Transactional(propagation = REQUIRES_NEW)` y adentro hace
el `SELECT ... FOR UPDATE SKIP LOCKED` (sección 5) y el `save()` del nuevo estado. Mientras la
transacción vive, la fila está lockeada y ninguna otra instancia la ve; cuando commitea, la
fila ya no está en `EVALUATING_RISK`, así que la query del poller no la vuelve a devolver.

**Matiz honesto**: esto es *at-least-once*, no *exactly-once*. Si el proceso muere después de
llamar a `RiskService` y antes del commit, la transacción hace rollback, el retiro vuelve a
estar visible y se re-evalúa. Es aceptable porque la evaluación de riesgo es una consulta sin
efecto externo: repetirla no cobra dos veces ni mueve plata. La condición que **no** tolera
at-least-once es C6, y por eso ahí el mecanismo es distinto (idempotency key + reconciliación,
no solo un lock).

**Fail-safe**: cualquier `RiskEvaluationException` (incluido el ~15% de fallos del servicio)
enruta a `PENDING_AUTHORIZATION`, nunca a `AUTHORIZED`:

```java
} catch (RiskEvaluationException e) {
    // fail-safe: an unknown risk is treated as high risk, never as automatic approval
    newStatus = WithdrawalStatus.PENDING_AUTHORIZATION;
}
```

La ausencia de respuesta no puede equivaler a una aprobación automática: cuando el riesgo es
desconocido decide una persona. Verificado en
`RiskEvaluationPollerTest.riskServiceFailureIsFailSafeToPendingAuthorization`, que además
chequea que `riskLevel` quede `null` (no se inventa un nivel para llenar el campo).

**Trade-off asumido**: la fila queda lockeada durante toda la llamada de 1-3s, y el lote de 5
se procesa secuencialmente dentro de la misma transacción (peor caso ~15s de lock). Es
defendible acá porque la evaluación de riesgo no tiene ambigüedad que reconciliar: es una
lectura sin efecto externo, así que un rollback es gratis. El poller de transferencia no puede
darse ese lujo (3-10s + timeout ambiguo) y por eso su diseño exige el split en dos fases.

**Alternativas descartadas**:

- *Evaluar en el request*: 1-3s de latencia y 15% de error en el hilo HTTP. Descartado por el
  enunciado mismo.
- *`@Async` con un executor de Spring*: la cola vive en memoria; si el proceso se reinicia se
  pierden los pendientes, y con 2 instancias no hay ninguna coordinación. Está pensado para
  trabajo disparado por una acción de usuario, no para una cola persistente.
- *Kafka / RabbitMQ*: resuelve el problema, pero agrega infraestructura y operación para un
  volumen que Postgres maneja sin transpirar. Para este alcance es sobre-ingeniería, y una
  cola en Postgres da persistencia y supervivencia a reinicios sin ningún componente nuevo.

---

## 5. C3 — Con 2+ instancias, ningún retiro se procesa dos veces ni queda sin procesar

**Mecanismo**: `SELECT ... FOR UPDATE SKIP LOCKED`, en
`WithdrawalRepository.lockNextBatchForRiskEvaluation`:

```sql
SELECT * FROM withdrawal
WHERE status = 'EVALUATING_RISK'
ORDER BY created_at
LIMIT :batchSize
FOR UPDATE SKIP LOCKED
```

`FOR UPDATE` lockea las filas devueltas; `SKIP LOCKED` hace que otra transacción concurrente
**salte** las filas ya lockeadas en vez de esperarlas. Con eso la tabla se comporta como una
cola multi-consumidor: cada instancia se lleva un conjunto disjunto de filas, sin elección de
líder, sin coordinador externo, sin heartbeats. Postgres es el árbitro.

- *No se procesa dos veces*: mientras una instancia tiene la fila lockeada, la otra ni la ve;
  cuando el lock se libera, la fila ya cambió de estado y no matchea el `WHERE`.
- *No queda sin procesar*: el poller no borra ni marca nada al reservar trabajo; si la
  transacción hace rollback (o el proceso muere), el lock cae con la conexión y la fila vuelve
  a estar disponible en el siguiente tick de cualquier instancia. No hay estado "en progreso"
  en memoria que se pueda perder.

Es JPA con query nativa a propósito: `LockModeType` de JPA no expone `SKIP LOCKED`, así que la
alternativa sería un hint específico de Hibernate; una query nativa deja explícito y legible
qué se está pidiendo.

**Verificación**: `RiskEvaluationPollerTest.concurrentPollersNeverEvaluateTheSameWithdrawalTwice`
lanza 2 pollers simulados (2 hilos = 2 instancias) haciendo 5 ticks cada uno sobre 12 retiros
(más que el batch de 5, para forzar varios lotes) y afirma que
`testRiskService.invocationCount(accountId) == 1` para cada uno, y que los 12 terminan
procesados. Las dos mitades de C3 en un solo test.

Detalle de testing que importa: el test invoca `poller.processBatch()` sobre el **bean
inyectado**, no sobre `this`. Si se invocara internamente, el proxy de Spring no se aplicaría,
no habría transacción real, y el test podría pasar sin probar nada.

**Evidencia real, no solo del test**: con `docker compose up` corriendo `backend-1` y
`backend-2` de verdad, 6 retiros creados de una sola vez terminaron repartidos así (logs
reales, filtrados por `claimed`):

```
[backend-1] risk evaluation claimed 1 withdrawal(s): [d0fd53d4-...]
[backend-2] risk evaluation claimed 5 withdrawal(s): [64ff6f88-..., ffc9ccdf-..., 68f86a04-..., 776cc9ef-..., 63cf4760-...]
[backend-1] transfer execution claimed 5 withdrawal(s): [64ff6f88-..., ffc9ccdf-..., 68f86a04-..., 776cc9ef-..., 63cf4760-...]
[backend-2] transfer execution claimed 1 withdrawal(s): [d0fd53d4-...]
```

Cero superposición de IDs entre instancias en cada etapa, y los 6 retiros llegaron a `EXECUTED`
sin ninguna intervención manual más allá del `POST` inicial.

**Alternativas descartadas**:

- *ShedLock (`@SchedulerLock`)*: hace lo contrario de lo que se necesita. Sirve para que un
  `@Scheduled` corra **una sola vez entre todas las instancias** (un reporte diario, por
  ejemplo). Acá queremos que todas las instancias corran en paralelo y se repartan filas
  distintas: con ShedLock una sola instancia trabajaría y la otra estaría de adorno.
- *Lock distribuido en Redis*: una dependencia más, un modo de falla más, y garantías más
  débiles que un lock de fila transaccional que ya viene con la base que igual necesitamos.
- *`FOR UPDATE` sin `SKIP LOCKED`*: correcto pero serializa las instancias — la segunda se
  queda esperando el mismo lote en vez de trabajar en otro.
- *Claim con `UPDATE ... SET status = 'CLAIMED' WHERE id IN (SELECT ...)`*: funciona, pero
  agrega un estado técnico al dominio (visible para el frontend y para compliance) y, si se
  quiere que dos instancias no compitan por el mismo subselect, igual necesita `SKIP LOCKED`
  adentro. Más piezas para la misma garantía.

---

## 6. C4 — Dos operadores sobre el mismo retiro: gana el primero, el otro se entera

**Mecanismo**: dos capas, en `WithdrawalService` + `GlobalExceptionHandler`.

**Capa 1 — precondición** (`WithdrawalService.loadForTransition`): si el retiro no está en
`PENDING_AUTHORIZATION`, tira `InvalidTransitionException` con el estado actual y el
`updated_by` actual. Cubre el caso no-competitivo: el operador abrió la pantalla hace cinco
minutos y el retiro ya se resolvió antes de que su request saliera.

**Capa 2 — la carrera real** (`@Version` en `Withdrawal`): si dos requests pasan la
precondición al mismo tiempo, ambos leen `version = N` y ambos intentan escribir. Hibernate
emite `UPDATE ... WHERE id = ? AND version = N`; el primero en commitear la sube a `N+1`, el
segundo afecta 0 filas y Spring lanza `ObjectOptimisticLockingFailureException` en el flush.
Determinístico: exactamente uno gana.

Las dos se mapean a **409 Conflict** con un body útil (`web/dto/ConflictResponse`):

```java
public record ConflictResponse(String error, String message, WithdrawalStatus currentStatus, String updatedBy) {}
```

El handler de la carrera re-lee el retiro por `ex.getIdentifier()` para devolver el estado real
del momento, así el frontend puede mostrar *"ya lo resolvió operator-juan"* y refrescar, en vez
de un error opaco. "El que pierde se entera" no es solo devolver un código de error: es
devolverle qué pasó realmente.

Tres detalles que hacen que esto funcione de verdad:

- `spring.jpa.open-in-view: false` (`application.yml`). Con OSIV activo el flush puede caer
  **después** de que empezó a escribirse la respuesta HTTP, y entonces el conflicto sale como
  500 en vez de 409.
- `reject()` libera la reserva de saldo **en la misma transacción** que la transición de
  estado. Si el `accountRepository.release(...)` estuviera en un `REQUIRES_NEW` o en un
  listener `AFTER_COMMIT`, el reject perdedor de una carrera podría llegar a liberar la reserva
  de un retiro que otro operador acaba de autorizar — plata liberada para un retiro vivo.
- `Withdrawal` sí tiene setters (a diferencia de `Account`) porque acá el camino correcto de
  escritura **es** el entity manager: es precisamente `@Version` en el flush lo que provee la
  garantía.

**Verificación**:
`WithdrawalServiceAuthorizationTest.concurrentAuthorizeAndRejectOnSameWithdrawal_exactlyOneWins`
corre `authorize` y `reject` en paralelo sobre el mismo retiro y afirma: exactamente 1 éxito,
exactamente 1 `ObjectOptimisticLockingFailureException`, estado final consistente, y —lo más
importante— que el `reserved_balance` final coincida con el que realmente ganó (0 si ganó
reject, el monto si ganó authorize). No alcanza con que "uno falle": tiene que no quedar
ningún efecto colateral del perdedor.

**Alternativas descartadas**:

- *Locking pesimista (`SELECT FOR UPDATE`) en el endpoint*: serializa a los operadores y
  sostiene un lock de fila desde un hilo HTTP, que es justo lo que C1 pide no hacer. Y el que
  espera termina viendo el estado ya cambiado igual, así que paga latencia para llegar al mismo
  resultado.
- *UPDATE condicional (`WHERE status = 'PENDING_AUTHORIZATION'`) en vez de `@Version`*: es
  equivalente en garantía y de hecho es el mecanismo que se usa en C5, donde no hay entidad
  gestionada de por medio. Acá se prefirió `@Version` porque el flujo ya trabaja con la entidad
  cargada (hay que leerla para validar y para escribir la historia), y porque protege **toda**
  transición del agregado, no solo la que uno se acordó de poner en el `WHERE`.
- *`SERIALIZABLE`*: obligaría a manejar reintentos por fallos de serialización en todo el
  sistema para resolver algo que una columna `version` resuelve puntualmente.

---

## 7. C5 — La suma de retiros vivos nunca excede el saldo disponible

**Mecanismo**: tres UPDATE condicionales en `repository/AccountRepository`, guardados por
`rowsAffected`. Nada de locks aplicativos.

```sql
-- tryReserve
UPDATE account
SET reserved_balance = reserved_balance + :amount
WHERE id = :accountId
  AND (balance - reserved_balance) >= :amount
```

La clave es que la **verificación y la escritura son la misma sentencia**, así no hay ventana
TOCTOU. La pregunta natural es: bajo `READ COMMITTED`, ¿la segunda transacción no evalúa el
`WHERE` contra una foto vieja de la fila? No: cuando un escritor se bloquea sobre una fila que
otra transacción está modificando, al liberarse el lock Postgres **re-evalúa la condición
contra la versión recién commiteada** (EvalPlanQual). Por eso el chequeo de saldo no puede leer
datos rancios, y por eso no hace falta `SELECT FOR UPDATE` previo ni subir el nivel de
aislamiento.

Cuando no hay saldo, el método devuelve **0 filas afectadas, no una excepción**: la falta de
saldo es un resultado esperable del dominio, y quien decide qué significa es el caller
(`WithdrawalService.createWithdrawal` lo traduce a `InsufficientBalanceException` → 409, y
distingue antes el caso "la cuenta no existe" → 404).

Los otros dos:

- `release` — devuelve la reserva sin tocar el saldo real (retiro rechazado o con error
  terminal). Guardado por `reserved_balance >= :amount`, así una doble ejecución nunca lo deja
  negativo.
- `settle` — debita el saldo real **y** libera la reserva en **una sola sentencia**:

  ```sql
  SET balance = balance - :amount, reserved_balance = reserved_balance - :amount
  ```

  Partirlo en dos statements dejaría una ventana en la que el dinero no está ni reservado ni
  debitado, y otro retiro concurrente podría reservar contra ese hueco.

Los `CHECK` de la tabla (`balance >= 0`, `reserved_balance >= 0`,
`balance >= reserved_balance`) son la red de último recurso: si algún día un camino de código
se saltea estas queries, la base rechaza la escritura en vez de corromper el saldo en silencio.

`createWithdrawal` hace la reserva y el insert del retiro en la **misma** `@Transactional`: si
algo falla después de reservar, la reserva se va con el rollback y no queda saldo comprometido
sin retiro asociado.

**Verificación**: `AccountReservationConcurrencyTest` es el test más importante del proyecto.
20 hilos liberados a la vez con un `CountDownLatch` contra una cuenta de 50.000 pidiendo 3.000
cada uno: exactamente 16 tienen que reservar y 4 tienen que ser rechazados, `reserved_balance`
final = 48.000, y el invariante que realmente importa,
`reservedBalance <= balance`. Está deliberadamente aislado de la máquina de estados y de los
pollers: si falla, la causa es el UPDATE y nada más.

**Bug real que encontró ese test**: la primera versión de las queries tenía `@Modifying` pero
no `@Transactional`. Los repositorios de Spring Data corren por default en una transacción
*read-only*, que Postgres rechaza para un `UPDATE`. El resultado no era una excepción clara
sino **0 filas afectadas en silencio** — el test dio "0 éxitos de 16 esperados" y expuso el
problema al instante. Es exactamente el tipo de bug que una revisión de código puede pasar por
alto y que un test de concurrencia sobre el invariante crítico no perdona. El javadoc de
`tryReserve` documenta el motivo para que nadie lo saque por parecer redundante.

**Limitación conocida**: un retiro en `RETRYABLE_ERROR` mantiene su reserva indefinidamente,
sin TTL. Es una decisión de alcance, no un olvido: liberar reservas por tiempo implica decidir
qué pasa si el banco confirma la transferencia después de la liberación, y eso es un problema
de reconciliación mucho más grande que el que el enunciado plantea. En producción se resolvería
con un job de expiración que mueve el retiro a `MANUAL_REVIEW` en vez de liberar plata solo.

**Alternativas descartadas**:

- *`SELECT FOR UPDATE` + chequeo en Java + `UPDATE`*: misma garantía, dos viajes a la base y
  una sección crítica más larga, además de poner en el código de aplicación un invariante que
  la base puede sostener sola.
- *Aislamiento `SERIALIZABLE`*: correcto, pero obliga a implementar reintentos por
  `serialization_failure` en toda la aplicación para proteger un único invariante.
- *Lock aplicativo (`synchronized`, o Redis)*: `synchronized` no sirve con 2 instancias; Redis
  agrega una dependencia con su propio modo de falla para algo que Postgres ya garantiza dentro
  de la misma transacción que escribe los datos.
- *Advisory locks de Postgres (`pg_advisory_xact_lock(accountId)`)*: funciona y es idiomático,
  pero serializa **toda** la actividad de una cuenta, y el UPDATE condicional consigue lo mismo
  con menos contención y sin un mecanismo de lock paralelo al de las filas.

---

## 8. C6 — Un retiro nunca genera dos transferencias reales

**Mecanismo**: cuatro piezas que se sostienen entre sí — una idempotency key por retiro,
`PROCESSING_TRANSFER` escrito y commiteado **antes** de la llamada externa, un UPDATE condicional
que impide la doble aplicación de un resultado, y una reconciliación que **consulta** en vez de
reintentar. Ninguna de las cuatro alcanza sola.

**1. Idempotency key por operación de negocio.** `withdrawal.idempotency_key` se genera una sola
vez en `createWithdrawal`, es `updatable = false` y `unique` en la base, y `transfer` guarda
exactamente el mismo valor (también `unique`). Todos los reintentos de un retiro usan la misma
clave —el claim del poller reutiliza la fila `transfer` existente en vez de crear una nueva
(`transferRepository.findByWithdrawalId(...).orElseGet(...)`)—, así que el banco puede
reconocerlos como el mismo intento. No es una idempotency key de request HTTP: es del intento de
negocio, que es lo que sobrevive a reintentos, a reinicios y a un operador apretando "reintentar"
media hora después.

**2. `PROCESSING_TRANSFER` es un compromiso persistido antes de llamar al banco.** La fase A del
`TransferExecutionPoller` (sección 3) no es solo un claim: antes de que salga un solo byte hacia
el banco, la base ya dice "este retiro tiene una transferencia en vuelo, con esta key, desde este
`requested_at`". Eso es lo que hace crash-safe al sistema — si el proceso muere en medio de la
llamada, o la respuesta vuelve 40 segundos tarde, o no vuelve nunca, el rastro no está en la
memoria del hilo que murió sino en una fila. El `ReconciliationPoller` busca exactamente esa fila
(`status = 'PROCESSING_TRANSFER'` con `t.requested_at` más viejo que el grace period) y la
resuelve. El orden es el único correcto posible: comprometerse primero, llamar después. Al revés
—llamar y recién después registrar que se llamó— existe una ventana en la que la plata se movió en
el banco sin que el sistema tenga ningún registro de haberlo intentado, y de ahí no se vuelve.
La misma query cubre gratis el caso "el proceso murió entre el claim y la llamada": el retiro
queda en `PROCESSING_TRANSFER` con un `requested_at` viejo y la reconciliación lo trata igual que
a un timeout.

**3. El guard contra la doble aplicación** (`WithdrawalRepository.transitionFromProcessingTransfer`):

```sql
UPDATE withdrawal
SET status = :newStatus,
    updated_at = now(),
    updated_by = :actor,
    transfer_id = COALESCE(:transferId, transfer_id)
WHERE id = :id
  AND status = 'PROCESSING_TRANSFER'
  AND (SELECT t.attempt_count FROM transfer t WHERE t.withdrawal_id = :id) = :expectedAttemptCount
```

Es el corazón de C6. Los cinco caminos de `TransferOutcomeService` que resuelven un intento pasan
por acá, y **todos chequean `rows == 0` y se van sin hacer nada** si el UPDATE no afectó ninguna
fila. `expectedAttemptCount` es el `attempt_count` que `processOne` leyó de la fila `transfer`
**antes** de llamar al banco (sección 3), no el actual al momento de aplicar el resultado.

La carrera concreta que ataja, y por qué el `status = 'PROCESSING_TRANSFER'` solo no alcanza: el
intento 1 hace timeout de cliente a los 15s pero la llamada real sigue viva contra el banco.
Mientras tanto la reconciliación consulta, ve `NOT_FOUND`, y `resetForRetryAfterReconciliation`
devuelve el retiro a `AUTHORIZED` — el `TransferExecutionPoller` lo reclama de nuevo, incrementa
`attempt_count` a 2 y el retiro vuelve a `PROCESSING_TRANSFER`, ahora para el intento 2. Si en ese
momento llega la respuesta tardía del intento 1 (el banco sí lo había aplicado) y llama a
`applySuccess`, un guard que solo mirara `status = 'PROCESSING_TRANSFER'` **matchearía igual** —el
retiro está en ese estado, solo que por el intento 2— y aplicaría el resultado del intento 1
encima del trabajo en curso del intento 2: doble débito o un `bankReference` que no corresponde al
intento que realmente está resolviéndose. El chequeo de `attempt_count` es lo que distingue "esta
fila sigue en el estado que autoriza mi escritura" de "esta fila volvió a este mismo estado por
otro motivo". El caso más simple —fase B tardía llegando después de que la reconciliación ya
resolvió el mismo intento a `EXECUTED`— también lo cubre, porque ahí ni siquiera el `status`
matchea.

Que el chequeo de `rows == 0` esté **antes** de `settle`/`release` y no después no es casual: el
efecto sobre el saldo es precisamente el que no se puede repetir. `COALESCE(:transferId,
transfer_id)` permite que la misma query sirva para las cinco transiciones sin borrar el
`transfer_id` en las cuatro que no lo traen.

Es un UPDATE condicional y no `@Version` (que es el mecanismo de C4, sección 6) porque acá el
escritor puede no tener la entidad cargada ni haber leído la fila en esta transacción: viene de un
hilo del executor, después de una llamada externa que duró segundos. La condición que importa no
es "nadie cambió la fila desde que la leí", es "la fila sigue exactamente en el estado que
autoriza esta escritura".

**4. La regla de oro: después de un timeout nunca se reintenta a ciegas, siempre se pregunta.**
`markAwaitingReconciliation` es el único camino que **no** resuelve nada sobre el retiro: deja el
retiro en `PROCESSING_TRANSFER` (por eso no pasa por `transitionFromProcessingTransfer` — no hay
transición de estado del retiro que guardar) y solo marca el `transfer` como
`AWAITING_RECONCILIATION`, vía `TransferRepository.markAwaitingReconciliationIfStillPending`. Esa
escritura tiene su propio guard, más chico pero con la misma idea: `WHERE id = :id AND status =
'PENDING'`. Cubre la carrera simétrica a la de arriba pero sobre `transfer` en vez de `withdrawal`
— si mientras la fase B todavía está en vuelo la reconciliación ya resolvió el mismo intento
(a `SUCCEEDED` o a un `FAILED_*`), el `status` del `transfer` ya dejó de ser `PENDING`, y esta
escritura tardía se vuelve un no-op en vez de pisar un `bank_reference`/`resolvedAt` reales con
"esperando reconciliación". Después, `ReconciliationPoller.reconcileOne`
consulta con `queryByIdempotencyKey` y mapea la respuesta del banco a un desenlace:

| Respuesta del banco | Acción | Resultado |
|---|---|---|
| `APPLIED` | `applySuccess` con la `bankReference` real | `EXECUTED` + débito |
| `FAILED_INVALID_ACCOUNT` | `applyInvalidAccount` | `FINAL_ERROR` + libera la reserva |
| `FAILED_OTHER` | `applyInternalError` | `RETRYABLE_ERROR`, reserva retenida |
| `NOT_FOUND` | `resetForRetryAfterReconciliation` | vuelve a `AUTHORIZED`, `transfer` a `PENDING` con `requested_at = null` — salvo que ya haya agotado los reintentos (ver abajo), en cuyo caso va directo a `MANUAL_REVIEW` |
| La consulta misma falla | `recordFailedReconciliationAttempt` | cuenta el intento; a los 5, `MANUAL_REVIEW` |

Solo la rama `NOT_FOUND` habilita un reintento, y lo hace porque el banco **confirmó** que no
aplicó nada. `resetForRetryAfterReconciliation` limpia `requested_at` a propósito, para que la
reconciliación no vuelva a marcar el mismo retiro como colgado antes de que el poller de
transferencia alcance a reintentarlo. Hay dos techos independientes en `TransferOutcomeService`,
los dos en `MANUAL_REVIEW`: `MAX_RECONCILIATION_ATTEMPTS` (5) para cuando la consulta al banco en
sí misma sigue fallando (`recordFailedReconciliationAttempt`), y `MAX_TRANSFER_ATTEMPTS` (5) para
cuando el banco contesta `NOT_FOUND` una y otra vez y el reintento automático nunca converge —
sin este segundo techo, un desenlace que siempre hace timeout sin aplicar reintentaría para
siempre (`NOT_FOUND` → `AUTHORIZED` → timeout → `NOT_FOUND` → …). Los dos casos terminan en la
misma cola humana porque un retiro que nadie puede explicar, o que nadie puede completar, tiene
que salir del loop automático y no quedarse en él indefinidamente.

**`MANUAL_REVIEW` tiene una salida real, no es un final sin operador.**
`POST /api/withdrawals/{id}/resolve-manual-review` → `WithdrawalService.resolveManualReview`
(requiere `X-Operator-Id`, igual que `authorize`/`reject`/`retry`) cierra el retiro a
`FINAL_ERROR` y libera la reserva en la misma transacción, con su fila en
`withdrawal_status_history`. Antes de esto `MANUAL_REVIEW` era alcanzable pero no tenía ningún
camino de salida en el código — un operador podía ver el retiro en esa cola pero no había endpoint
que le permitiera cerrarlo, y la reserva de saldo quedaba tomada indefinidamente. Verificado por
`ReconciliationPollerTest.repeatedReconciliationLookupFailuresEscalateToManualReviewWithOperatorExit`
(sección 11), que fuerza los 5 fallos de lookup, confirma el escalamiento y después llama
`resolveManualReview` para comprobar que la reserva se libera.

El grace period (`grace-period-seconds: 30`) tiene que ser mayor que el timeout de cliente (15s),
si no la reconciliación empezaría a preguntar por transferencias que todavía están legítimamente
en vuelo. La comparación se hace con `now()` de Postgres y no con el reloj de la JVM: con 2+
instancias hay clock skew, y lo que decide qué fila es elegible tiene que salir de un solo reloj
(sección 12).

**El lock de la reconciliación es sobre `withdrawal`, no sobre `transfer`.**
`lockNextBatchForReconciliation` joinea `transfer` para filtrar por `requested_at`, pero lockea con
`FOR UPDATE OF w SKIP LOCKED`, o sea únicamente las filas de `withdrawal`. Es deliberado: el
`TransferExecutionPoller` también lockea `withdrawal`, así que los dos pollers comparten **una
sola sección crítica por agregado**. Con la reconciliación lockeando `transfer` habría dos
esquemas de locking independientes sobre el mismo agregado, y podrían cruzarse — el poller de
transferencia tomando el retiro mientras la reconciliación tiene tomado su transfer, cada uno
creyendo que es dueño de la operación.

Además, el claim de la reconciliación **bumpea `requested_at` dentro de la misma transacción que
sostiene el lock**. Sin esa escritura el lock se soltaría al commitear la transacción corta del
claim, y la fila —que sigue matcheando el mismo filtro de "más vieja que el grace period"— podría
ser tomada por otra instancia antes de que `reconcileOne` termine. Es el equivalente a lo que el
poller de transferencia obtiene gratis al escribir `PROCESSING_TRANSFER` en su propio claim; la
reconciliación no tiene un estado nuevo al que mover el retiro, así que reutiliza `requested_at`
como marca de claim. Si la resolución nunca termina, la fila vuelve a ser elegible después de otro
grace period completo: se auto-cura, no queda tomada para siempre.

**El mock del banco implementa la semántica exacta que hace que C6 sea demostrable**
(`external/mock/BankServiceMock`, replicada en `TestBankService` para los tests):

| Resultado | ¿Se cachea por key? | Por qué |
|---|---|---|
| Éxito | Sí, terminal | Reejecutar la misma key devuelve la misma `bankReference` sin volver a transferir |
| `InvalidAccountException` | Sí, terminal | Reintentar es inútil; el reintento vuelve a fallar igual |
| `InternalErrorException` | **No** | Cada llamada es un intento independiente; cachearlo dejaría al operador en un loop de error permanente del que no puede salir |
| `BankTimeoutException` | Se persiste la **verdad de fondo** (aplicó / no aplicó) *antes* de lanzar la excepción | Es lo único que le da a la reconciliación algo real que consultar en vez de adivinar |
| Misma key en vuelo | `IdempotentRequestInProgressException` | Impide dos ejecuciones concurrentes de la misma transferencia, que es la carrera que C6 tiene que resistir |

`queryByIdempotencyKey` es la consulta rápida que la reconciliación va a usar: `executeTransfer`
nunca resuelve por su cuenta un timeout ambiguo. Un timeout con `applied = false` se reporta
como `NOT_FOUND` (equivalente a "no pasó nada, es seguro reintentar"); uno con `applied = true`
se reporta como `APPLIED` con su `bankReference`, y en ese caso reintentar sería un doble pago.

*Detalle que importa bajo C3*: esa verdad de fondo vive en Postgres (`bank_mock_ledger` /
`bank_mock_in_flight`, `V3__bank_mock_ledger.sql`), no en un `Map` dentro del proceso. Un banco
real tiene una sola verdad de fondo sin importar quién pregunta; un mapa en memoria le daba a
este mock una verdad **distinta por instancia**. Con 2+ backends (la demo de docker-compose),
`backend-1` podía cachear en su propio heap un resultado que la reconciliación corriendo en
`backend-2` — otra JVM — nunca iba a ver, y lo leía como `NOT_FOUND` cuando en realidad ya había
una respuesta. La exclusión mutua de `executeTransfer` (antes un `Set` en memoria) también se
persiste: la clave primaria de `bank_mock_in_flight` es lo que hace atómico el claim entre
instancias, vía `INSERT ... ON CONFLICT DO NOTHING`.

*Supuesto explícito*: el enunciado no dice que el banco ofrezca una consulta por idempotency
key. Se asumió porque sin ella un timeout es irresoluble — cualquier decisión (reintentar o
dar por fallido) sería adivinar, y una de las dos ramas duplica plata. Es el supuesto más
fuerte del diseño y está declarado como tal en la sección 12.

**Verificación de esa semántica**: `BankServiceMockTest` cubre los cinco casos (error interno
no cacheado y reintentable, cuenta inválida terminal y cacheada, timeout aplicado visible como
`APPLIED`, timeout no aplicado visible como `NOT_FOUND` y reintentable, éxito replayable con la
misma `bankReference`). `TestBankService` replica la misma semántica sin la latencia, así que los
tests de los pollers ejercitan el contrato real y no una versión simplificada de él.

**Verificación de la condición**: `ReconciliationPollerTest`, los dos escenarios que importan, de
punta a punta contra Postgres real:

- `timeoutThatDidApplyIsResolvedToExecutedByReconciliation` — timeout con la transferencia
  **sí** aplicada. El poller de transferencia deja el `transfer` en `AWAITING_RECONCILIATION`,
  pasa el grace period, la reconciliación consulta, ve `APPLIED` y resuelve a `EXECUTED` con
  débito real (100.000 → 99.900) y la `bankReference` que dio el banco. Nunca se vuelve a llamar
  a `executeTransfer`: la plata ya se movió una vez y el sistema se limita a enterarse.
- `timeoutThatNeverAppliedIsResetAndSucceedsOnAutomaticRetry` — timeout con la transferencia
  **no** aplicada. La consulta devuelve `NOT_FOUND`, el retiro vuelve a `AUTHORIZED` con el
  `transfer` en `PENDING` y la reserva intacta (el timeout por sí solo nunca toca el saldo), y el
  poller de transferencia lo reintenta solo hasta `EXECUTED`. La aserción que cierra C6:
  `testBankService.executionCount(idempotencyKey) == 2` — exactamente dos llamadas reales al banco
  en toda la historia del retiro (la que hizo timeout y el reintento), las dos con la **misma**
  idempotency key, y un único débito de 100.

La única concesión de estos tests es bajar `grace-period-seconds` a 2 en `application-test.yml`
para no dormir 30 segundos; la lógica ejercitada es exactamente la de producción.

**Alternativas descartadas**:

- *Reintentar automáticamente después de un timeout, sin consultar antes*: es el procedimiento
  exacto para generar una transferencia doble. Un timeout significa "no sé", y una parte de las
  veces significa "el banco aplicó y la respuesta se perdió"; un reintento ciego en ese caso manda
  una segunda transferencia real. La única razón por la que acá no duplicaría es que el mock
  cachea por idempotency key — y apoyarse en eso sería confundir la garantía del banco con la del
  propio sistema: si el banco no cacheara, o cacheara por menos tiempo del que tarda el reintento,
  sale la plata dos veces. El diseño no depende de la buena voluntad del proveedor: pregunta
  primero.
- *Dar el timeout por fallido y liberar la reserva*: la rama opuesta, y es peor. Si el banco sí
  aplicó, se libera saldo de un retiro que ya se pagó y la cuenta puede volver a comprometer plata
  que no tiene. Descartado por el mismo motivo: es adivinar sobre un resultado que se puede
  consultar.
- *Que la reconciliación lockee la fila `transfer`*: es lo primero que uno haría —el que está en
  `AWAITING_RECONCILIATION` es el `transfer`— pero rompe la unificación de la sección crítica que
  se explica arriba y deja dos esquemas de locking sobre el mismo agregado.
- *Un estado `RECONCILING` explícito en vez de bumpear `requested_at`*: funcionaría, pero agrega
  un estado técnico más a la parte más cargada del dominio, y necesita su propia recuperación
  cuando un proceso muere justo en ese estado. Marcar con un timestamp que ya existe, con
  elegibilidad definida por tiempo, se auto-cura sin código adicional.
- *Confiar solo en el `UNIQUE` de la base*: la unicidad de `withdrawal.idempotency_key` y de
  `transfer.idempotency_key` está y sirve, pero solo impide dos **filas**. No impide dos
  **llamadas** al banco con la misma fila, que es la duplicación que mueve plata.
- *Idempotency key por request HTTP*, el patrón clásico de API: protege contra el doble click del
  cliente al crear el retiro, no contra el reintento que el sistema hace contra el banco tres
  minutos más tarde. Son dos problemas distintos y C6 es el segundo.

---

## 9. Por qué Postgres y no MongoDB

MongoDB es la base con la que tengo más horas de vuelo, y aun así este problema pide Postgres.
La elección se hizo por los requisitos, no por comodidad:

1. **Transacciones ACID multi-tabla.** Reservar saldo, insertar el retiro y escribir la fila de
   historia tienen que commitear juntos o no commitear (`WithdrawalService.createWithdrawal`).
   Lo mismo con `reject()`: transición + liberación de reserva en el mismo commit, que es
   literalmente lo que evita el bug de C4 descrito en la sección 6. Mongo tiene transacciones
   multi-documento desde la 4.0, pero son la excepción del modelo, no el caso normal, y traen
   sus propias restricciones operativas (requieren replica set, tienen límites de duración).
2. **`FOR UPDATE SKIP LOCKED` nativo.** Es la pieza central de C3 y no tiene equivalente
   directo en Mongo. Lo más parecido es un `findOneAndUpdate` como claim atómico: sirve, pero
   es de a un documento por vez, no expresa "traeme un lote saltando lo que otro tiene tomado",
   y el "lock" pasa a ser un campo de estado que hay que limpiar a mano si el proceso muere,
   en vez de un lock transaccional que se libera solo al caer la conexión.
3. **UPDATE condicional atómico sobre un invariante numérico.** `tryReserve` y su
   `rowsAffected` son la implementación completa de C5. Es justo que en esto Mongo también es
   fuerte (`findAndModify` con condición es atómico a nivel documento); la diferencia no está
   en ese punto aislado sino en tenerlo junto con lo demás.
4. **Constraints declarativas como red de seguridad.** `CHECK (balance >= reserved_balance)`,
   `CHECK (destination_cbu ~ '^[0-9]{22}$')`, `CHECK (amount > 0)`, FKs y `UNIQUE` sobre las
   idempotency keys. En un dominio donde el peor bug posible es plata mal contabilizada, que la
   base rechace el dato inválido aunque la aplicación tenga un bug tiene valor real. En Mongo
   habría que sostener eso con JSON Schema validation o solo con código de aplicación.

Dicho al derecho: si el dominio fuera documentos heterogéneos con esquema variable y consultas
mayormente de lectura, elegiría Mongo sin dudarlo. Acá el dominio es un ledger con invariantes
numéricos bajo concurrencia, que es el caso canónico de una relacional.

---

## 10. Por qué Gradle

Por familiaridad, no por una ventaja técnica objetiva sobre Maven en este proyecto. No hay
build multi-módulo, ni tareas custom, ni nada donde el DSL de Gradle rinda de verdad. Prefiero
decirlo así antes que inventar una justificación técnica que el código no respalda.

Lo único que en este `build.gradle` no es boilerplate son dos workarounds de entorno reales,
que igual hubieran hecho falta con Maven:

- Un override del BOM de Testcontainers: Spring Boot 3.3.4 fija `testcontainers-core` 1.19.8,
  que falla al hablar con las versiones actuales de Docker Desktop
  (`BadRequestException` en `/info`). Se importa `testcontainers-bom:1.21.3`.
- Forzar `DOCKER_API_VERSION=1.51` en el JVM forkeado de los tests: el daemon de Docker acá
  exige `MinAPIVersion 1.44`, pero `docker-java` usa un default hardcodeado (1.32) para algunas
  llamadas internas. Se setea como variable de entorno del proceso de test (no del daemon de
  Gradle, que ya está arrancado y es poco confiable de modificar) para que aplique a **todas**
  las llamadas y no solo a algunas.

---

## 11. Testing

**Criterio**: pocos tests, sobre lo que puede romper plata. No se buscó cobertura amplia; se
buscó que cada condición crítica con concurrencia tenga un test que la ejercite de verdad.

| Test | Qué cubre |
|---|---|
| `AccountReservationConcurrencyTest` | **C5** — 20 hilos simultáneos contra la misma cuenta; exactamente 16 reservas, 4 rechazos, `reserved <= balance` |
| `RiskEvaluationPollerTest` | **C2/C3** — auto-autorización LOW, `PENDING_AUTHORIZATION` en HIGH, fail-safe ante fallo del servicio, 2 pollers concurrentes que nunca evalúan dos veces el mismo retiro, y un test dedicado que pasa por el `tick()` real (no `processBatch()` directo) — guarda de regresión para el bug de auto-invocación: ningún otro test de esta clase habría detectado que `self.processBatch()` volviera a ser `this.processBatch()` |
| `WithdrawalServiceAuthorizationTest` | **C4** — authorize/reject felices, precondición de estado inválido, y la carrera authorize-vs-reject con verificación del saldo reservado final |
| `TransferExecutionPollerTest` | **C1/C6** — los 4 desenlaces del banco: éxito (settle + `EXECUTED`), cuenta inválida (`FINAL_ERROR` + libera reserva), error interno (`RETRYABLE_ERROR` + reserva intacta), timeout (`AWAITING_RECONCILIATION`, retiro nunca sale de `PROCESSING_TRANSFER`); más **C3** para `lockNextBatchForTransfer` — 12 retiros, 2 pollers concurrentes, `executionCount == 1` para cada uno |
| `ReconciliationPollerTest` | **C6** — timeout que sí se aplicó resuelto a `EXECUTED`; timeout que no se aplicó, reseteado y reintentado con éxito, verificando `executionCount == 2` (nunca una tercera llamada real al banco); fallas repetidas del lookup de reconciliación escalando a `MANUAL_REVIEW` con salida real vía operador; más **C3** para `lockNextBatchForReconciliation` (`concurrentPollersResolveEachStuckWithdrawalWithoutDoubleApplying`) — 12 retiros stuck, 2 pollers concurrentes. A diferencia de los tests de concurrencia de `RiskEvaluationPollerTest`/`TransferExecutionPollerTest`, acá no se afirma que la consulta al banco corra exactamente una vez: el claim de la reconciliación solo bumpea `requested_at` (no cambia el status), así que una resolución lenta puede legítimamente dejar un retiro elegible de nuevo después de otro grace period — self-healing a propósito, no un bug. Lo que sí es una garantía real, y lo que el test afirma, es que el estado final nunca se aplica dos veces (status `AUTHORIZED`, `transfer` en `PENDING`, reserva intacta en 100.00 para cada uno de los 12), gracias al guard de `transitionFromProcessingTransfer` |
| `WithdrawalSearchTest` | Filtros del listado (status, rango de fechas, búsqueda por CBU parcial o cuenta exacta) |
| `BankServiceMockTest` | Contrato de idempotencia sobre el que se apoya **C6** (5 escenarios de caché + query). Corre contra Postgres real (no una unidad pura): desde que la verdad de fondo del mock vive en `bank_mock_ledger`/`bank_mock_in_flight` (sección 8), ya no hay lógica en memoria que aislar de la base |
| `RiskServiceMockTest` | Convención de forzado por decimales (barato, evita que un cambio la rompa en silencio y arruine las demos) |
| `AccountRepositoryTest` | Sanity de Flyway y del mapeo de la entidad |

**Postgres real vía Testcontainers, no H2.** `SKIP LOCKED` y el comportamiento de EvalPlanQual
bajo `READ COMMITTED` son justamente lo que se está testeando; contra H2 los tests pasarían sin
probar nada de lo que importa. Un test de concurrencia contra un motor que no reproduce la
semántica de locking del motor de producción es peor que no tener test, porque da confianza
falsa.

Dos decisiones de infraestructura de test que salieron de problemas reales:

- **Container singleton** en `AbstractIntegrationTest`, deliberadamente *sin*
  `@Testcontainers`/`@Container`: esa extensión ata el ciclo de vida del container a cada clase
  de test, pero el campo estático es compartido por todas las subclases — el `afterAll` de una
  clase frenaba el container mientras otra lo seguía usando, con fallos intermitentes de
  conexión. Se arranca una vez en un bloque estático y lo limpia el shutdown hook de
  Testcontainers.
- **`scheduling.enabled=false` en el perfil `test`** (`config/SchedulingConfig` con
  `@ConditionalOnProperty`): si los `@Scheduled` siguen latiendo durante el test, compiten con
  las invocaciones explícitas del test y lo vuelven no determinístico. Los tests manejan el
  poller a mano.
- **Dobles de test dedicados**: `TestRiskService` / `TestBankService` (perfil `test`) son
  instantáneos, programables por cuenta/key y **cuentan invocaciones** — sin ese contador no se
  puede afirmar "se evaluó exactamente una vez". Los mocks realistas (`RiskServiceMock`,
  `BankServiceMock`, con sus 1-3s y 3-10s) quedan para la demo manual, y su lógica de
  forzado/caché se testea directamente con el constructor de latencia casi cero, sin duplicar
  la semántica en dos lugares distintos.

**Qué NO se testeó, a propósito**:

- *Controllers con MockMvc*: son mapeo delgado a `WithdrawalService` más validación
  declarativa de Bean Validation. El comportamiento interesante está en el servicio y está
  cubierto contra una base real.
- *La distribución aleatoria de los mocks* (que el riesgo falle ~15% de las veces, etc.):
  testear un generador aleatorio contra sí mismo es flaky y no valida nada del dominio. Lo que
  sí se testea es la convención de forzado determinística.
- *Tests unitarios del servicio con Mockito*: mockear el repositorio eliminaría exactamente
  aquello que se quiere probar — el comportamiento de Postgres bajo concurrencia. Un test verde
  ahí no diría nada sobre C4 o C5.
- *Getters, setters, DTOs y mapeos triviales.*

**Los tests encontraron bugs reales**, que es el argumento de que no son decorativos: el bug de
`@Modifying` sin `@Transactional` (sección 7), y un bug de aislamiento entre tests
(`AccountRepositoryTest` afirmaba que la tabla `account` tenía exactamente 3 filas, lo que se
rompió apenas otras clases empezaron a compartir el mismo Postgres e insertar sus propias
cuentas; ahora afirma que existen las filas sembradas específicas).

---

## 12. Supuestos ante ambigüedades del enunciado

- **CBU = exactamente 22 dígitos**, validado en dos capas: `@Pattern(regexp = "^[0-9]{22}$")`
  en `CreateWithdrawalRequest` y `CHECK (destination_cbu ~ '^[0-9]{22}$')` en la tabla. **No**
  se valida el dígito verificador real del CBU: es un algoritmo conocido y trivial de agregar,
  pero no aporta nada a las condiciones que el challenge evalúa.
- **Las cuentas se crean y fondean por seed de Flyway** (`V2__seed_data.sql`), no por un
  endpoint. El enunciado no pide administración de cuentas, así que agregar un CRUD sería una
  feature no pedida. Son tres cuentas, y `ACC-002` está sembrada con 50.000 —cerca de su
  límite— para que un burst de retiros concurrentes choque contra C5 de forma visible en la
  demo.
- **La identidad del operador es un header `X-Operator-Id`**, confiado tal cual, sin auth (ver
  sección 13). Se registra igual en cada transición y en `withdrawal_status_history`: la
  ausencia de autenticación no es excusa para perder la trazabilidad.
- **Riesgo bajo y medio se autorizan solos; alto va a revisión humana.** Implementado como
  `LOW`/`MEDIUM → AUTHORIZED`, `HIGH → PENDING_AUTHORIZATION`, y **fallo del servicio →
  `PENDING_AUTHORIZATION`** (el enunciado pide esto último explícitamente y coincide con el
  criterio fail-safe).
- **Moneda única implícita**: `numeric(18,2)` sin columna de moneda. Multi-moneda cambiaría el
  modelo de saldos y no está pedido.
- **El "saldo disponible" de C5 es `balance - reserved_balance`**, es decir, cuentan como
  retiros vivos los que están en evaluación, pendientes de autorización, autorizados y en
  proceso de transferencia. Solo `REJECTED` y los errores terminales devuelven la reserva.
- **El banco expone una consulta por idempotency key** (`queryByIdempotencyKey`). No está en el
  enunciado; es el supuesto más fuerte del diseño y sin él el timeout no tiene solución
  correcta (ver sección 8).
- **Convención de forzado por decimales del monto** para poder demostrar cada rama sin tocar
  código ni recompilar: `.96/.97/.99` fuerzan riesgo LOW/MEDIUM/HIGH, `.98` fuerza fallo del
  servicio de riesgo; `.13` error interno del banco, `.14` timeout que **sí** aplicó, `.15`
  timeout que **no** aplicó, `.16` cuenta inválida, `.50` éxito garantizado. Es un invento
  propio para hacer demostrable el sistema, no un requisito.
- **`account_number` es `varchar(20)`**, lo que obliga a los tests a sembrar identificadores
  cortos (`ACC-` + 8 chars) en vez de un UUID completo.
- **Timestamps**: los de auditoría se escriben con el reloj de la JVM (`Instant.now()`). Las
  comparaciones temporales que deciden comportamiento **entre instancias** (el grace period de
  la reconciliación) usan `now()` de Postgres — `lockNextBatchForReconciliation` compara contra
  el reloj del motor, no el de ninguna JVM, precisamente porque con 2+ instancias hay skew de
  reloj (sección 8).

---

## 13. Qué se dejó afuera a propósito

- **Autenticación y autorización reales.** El operador llega como header `X-Operator-Id` sin
  verificar. En una versión completa: OIDC/JWT con un rol `OPERATOR`, y el actor del audit
  trail tomado del token verificado y no de un header que cualquiera puede escribir —
  en un dominio CNV, una traza que se puede falsificar no es una traza. El costo es acotado
  (un `SecurityFilterChain` y cambiar la fuente del `operatorId`); se dejó afuera porque el
  enunciado pide el backoffice, no el control de acceso.
- **UI de cliente para crear retiros.** Solo se pidió el backoffice de operadores. El endpoint
  `POST /api/withdrawals` existe igual, porque sin él no hay forma de generar carga para
  demostrar C5 y los pollers; se usa por API/curl, que además hace fácil disparar los 20
  concurrentes.
- **Límite de reintentos y backoff automático.** Hoy no hay reintento automático de
  `RETRYABLE_ERROR`: reintenta el operador. En una versión completa iría backoff exponencial
  con jitter y un máximo de intentos que termina en `MANUAL_REVIEW` — la columna
  `transfer.attempt_count` ya existe para eso, y `reconciliation_attempts` cumple ese rol del
  lado de la reconciliación.
- **Notificaciones al cliente** (mail/push al cambiar de estado). El detalle no trivial es que
  no se puede notificar dentro de la transacción (si hace rollback, ya se avisó de algo que no
  pasó) ni después sin garantía de entrega: la forma correcta es un patrón outbox, escribiendo
  el evento en la misma transacción y despachándolo con un poller — exactamente el mismo patrón
  que ya usan los pollers de este proyecto.
- **TTL / expiración de reservas** (ver la limitación conocida en la sección 7).
- **Observabilidad más allá de `health`/`info`.** En producción harían falta métricas de
  profundidad de cola por estado, latencia del banco y contador de reconciliaciones — es lo
  primero que se agregaría en una versión real, porque un poller que deja de drenar su cola es
  invisible hasta que alguien se queja.
- **Paginación y exportación regulatoria** del listado.

---

## 14. Uso de IA

Se usó **Claude Code de forma activa e intensiva** para construir este proyecto: el enunciado
lo permite explícitamente y no tiene sentido presentarlo de otra manera. Cómo se usó, con la
misma honestidad:

- **Antes de escribir código** se armó un plan técnico y se lo sometió a **dos rondas de
  auditoría cruzada** con agentes distintos (uno con perfil de tech lead / owner de producto,
  otro especializado en concurrencia). La segunda ronda encontró seis problemas reales en el
  diseño original: el pool de scheduling compartido en un solo hilo (sección 3), el settle de
  saldo partido en dos statements (sección 7), el hueco de la reconciliación en el que una
  llamada tardía al banco podía cruzarse con un reintento (sección 8), la semántica del mock de
  banco mal definida (que es lo que hace testeable a C6), y dos más. Todos entraron al diseño
  antes de la primera línea de código.
- **La verificación fue con tests, no con lectura.** Los tests de concurrencia encontraron
  bugs reales que ninguna revisión había visto. El más claro: `@Modifying` sin `@Transactional`
  en `AccountRepository`. Los repositorios de Spring Data corren en transacción read-only por
  default y Postgres rechaza el `UPDATE`, pero el fallo era **silencioso** —0 filas afectadas,
  sin excepción— así que el código parecía correcto leyéndolo. El test de 20 hilos devolvió "0
  éxitos, esperados 16" y expuso el problema de inmediato. Ese es el motivo por el que
  `AccountReservationConcurrencyTest` acumula las excepciones de cada hilo en vez de
  descartarlas: una excepción tragada ya escondió un bug real una vez.
- **Qué aportó la IA y qué se validó**: el diseño de fondo (las tablas, la máquina de estados,
  el mapeo condición → mecanismo) salió del trabajo con la IA y sobrevivió a la auditoría. Se
  validó entendiendo el razonamiento de cada pieza, no aceptando el resultado: por qué
  `SKIP LOCKED` y no ShedLock (hacen cosas opuestas, sección 5), por qué UPDATE condicional y
  no `SELECT FOR UPDATE` (EvalPlanQual, sección 7), por qué `@Version` y no locking pesimista
  (sección 6). Cada una de esas decisiones tiene su alternativa descartada escrita arriba
  porque se discutió, no porque quede lindo en un documento.
- **También hubo cosas que llevaron tiempo y no fueron código**: un problema real de
  compatibilidad entre Testcontainers y la versión de Docker Desktop de esta máquina obligó a
  diagnosticar en capas (versión del daemon, transporte, variables de entorno vs system
  properties del JVM forkeado) hasta dar con la combinación que funciona, que es lo que quedó
  documentado en `build.gradle` (sección 10).
- **Los mensajes de commit son deliberadamente explicativos** y describen el porqué de cada
  decisión y qué verificó cada test. El historial de git es parte de la entrega: sirve para
  seguir en qué orden se construyó el sistema y con qué criterio.
