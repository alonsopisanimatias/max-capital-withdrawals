# Max Capital — Portal de Retiros

Challenge técnico para un puesto de Full Stack Engineer: un portal de retiros donde un cliente pide un
retiro, el sistema evalúa el riesgo de forma asíncrona, un operador autoriza o rechaza los que necesitan
revisión manual, y la plata se transfiere a través de un servicio de banco externo (mockeado).

## Stack

| Capa | Tecnología |
| --- | --- |
| Backend | Java 21, Spring Boot 3.3.4, Gradle 8.10 (wrapper incluido) |
| Persistencia | PostgreSQL 16, Spring Data JPA, migraciones Flyway |
| Tests | JUnit 5, Spring Boot Test, Testcontainers 1.21.3 (Postgres real, no H2) |
| Frontend | React 18, TypeScript 5.6, Vite 5.4, TanStack Query 5 |

El proyecto Gradle vive en `backend/` (`backend/gradlew`), así que todos los comandos de Gradle de abajo
se corren desde esa carpeta.

## Requisitos previos

- JDK 21 (el toolchain de Gradle apunta a Java 21)
- Docker Desktop corriendo (hace falta para Postgres, y lo requiere la suite de tests)
- Node.js 18+ (solo para el frontend)

## Cómo correrlo localmente

### Opción A — todo junto, vía docker compose

```bash
docker compose up -d --build
```

Esto construye y levanta Postgres, **dos instancias del backend** (`backend-1` en `localhost:8081`,
`backend-2` en `localhost:8082`) y el frontend (`localhost:5173`, build estático servido por nginx, que
proxea `/api` a `backend-1` — ver [Estado del proyecto](#estado-del-proyecto)). Cada backend loguea su
`INSTANCE_ID`, así que se puede ver a las dos instancias reclamando filas disjuntas desde los pollers
(`docker compose logs -f backend-1 backend-2 | grep claimed`). Salud:

```bash
curl -s http://localhost:8081/actuator/health
curl -s http://localhost:8082/actuator/health
```

Da lo mismo hablarle a cualquiera de las dos instancias en los ejemplos de la API de abajo — comparten el
mismo Postgres. Para bajar todo: `docker compose down` (agregar `-v` para borrar también el volumen con los
datos sembrados).

### Opción B — backend corriendo local contra un Postgres en contenedor

Útil para iterar sobre el backend sin reconstruir la imagen cada vez.

```bash
docker compose up -d postgres
cd backend
./gradlew bootRun          # Windows: .\gradlew.bat bootRun
```

Base `withdrawals`, usuario `withdrawals`, contraseña `withdrawals`, publicado en `localhost:5432`. Los
valores por defecto en `backend/src/main/resources/application.yml` ya apuntan ahí, así que no hace falta
ninguna variable de entorno. Se pueden sobreescribir si tu setup es distinto:

| Variable | Default |
| --- | --- |
| `DB_HOST` | `localhost` |
| `DB_PORT` | `5432` |
| `DB_NAME` | `withdrawals` |
| `DB_USER` | `withdrawals` |
| `DB_PASSWORD` | `withdrawals` |
| `SERVER_PORT` | `8080` |
| `INSTANCE_ID` | `local` (tagea los logs de claim de los pollers; `backend-1`/`backend-2` bajo compose) |

Flyway corre las migraciones al arrancar y siembra tres cuentas de demo (ver abajo).

```bash
curl -s http://localhost:8080/actuator/health
curl -s http://localhost:8080/api/accounts   # tres filas significa que todo el stack está bien conectado
```

### Frontend

```bash
cd frontend
npm install
npm run dev            # http://localhost:5173
```

`npm run dev` proxea `/api` a `http://localhost:8080` por defecto (un backend corriendo local, Opción B de
arriba) — sobreescribilo con `VITE_API_PROXY_TARGET` si en cambio querés apuntar al `8081`/`8082` de
`docker compose`:

```bash
VITE_API_PROXY_TARGET=http://localhost:8081 npm run dev
```

Bajo `docker compose up` (Opción A), no hace falta ninguna variable de proxy — nginx se encarga
(`frontend/nginx.conf`).

## Tests

La suite de tests usa Testcontainers, que levanta su propio Postgres descartable. **No** usa la base de
`docker-compose.yml` — solo necesita un daemon de Docker corriendo.

```bash
cd backend
./gradlew test         # Windows: .\gradlew.bat test
```

Reporte HTML: `backend/build/reports/tests/test/index.html`.

## Probando la API a mano

### Cuentas sembradas (`V2__seed_data.sql`)

| ID | Número de cuenta | Titular | Balance |
| --- | --- | --- | --- |
| `11111111-1111-1111-1111-111111111111` | ACC-001 | Juan Perez | 1.000.000,00 |
| `22222222-2222-2222-2222-222222222222` | ACC-002 | Maria Gomez | 50.000,00 |
| `33333333-3333-3333-3333-333333333333` | ACC-003 | Carlos Ruiz | 250.000,00 |

`ACC-002` está sembrada a propósito cerca de un límite realista, para que un burst de retiros concurrentes
choque visiblemente contra saldo insuficiente.

### Crear un retiro

```bash
curl -i -X POST http://localhost:8080/api/withdrawals \
  -H "Content-Type: application/json" \
  -d '{
    "accountId": "11111111-1111-1111-1111-111111111111",
    "destinationCbu": "0170099220000067797151",
    "amount": 1500.99
  }'
```

`destinationCbu` tiene que ser de exactamente 22 dígitos y `amount` tiene que ser positivo, con hasta 16
dígitos enteros y 2 decimales (coincidiendo con la columna `numeric(18,2)`). La respuesta es `201` con el
retiro en `EVALUATING_RISK` y `riskLevel: null` — el riesgo se evalúa fuera del hilo del request, por un
poller de background, así que el estado cambia unos segundos después. Seguilo con el endpoint de detalle,
que también trae el intento de transferencia una vez que existe:

```bash
curl -s http://localhost:8080/api/withdrawals/<withdrawal-id>
curl -s "http://localhost:8080/api/withdrawals?size=5&sort=createdAt,desc"   # o la grilla completa
```

La base de datos sigue siendo la forma más rápida de ver varios de un vistazo mientras corre una prueba de
carga:

```bash
docker compose exec postgres psql -U withdrawals -d withdrawals \
  -c "select id, status, risk_level, updated_by from withdrawal order by created_at desc limit 5;"
```

Riesgo `LOW`/`MEDIUM` mueve el retiro a `AUTHORIZED` automáticamente; riesgo `HIGH` — y cualquier falla del
servicio de riesgo, que a propósito se trata como riesgo alto — lo mueve a `PENDING_AUTHORIZATION`, donde un
operador tiene que resolverlo:

```bash
curl -i -X POST http://localhost:8080/api/withdrawals/<withdrawal-id>/authorize \
  -H "X-Operator-Id: operator-1"
```

De `AUTHORIZED` en adelante no hace falta llamar nada a mano: el poller de ejecución de transferencia
reclama el retiro en su próximo tick (cada segundo), lo mueve a `PROCESSING_TRANSFER`, llama al banco y lo
resuelve como `EXECUTED`, `RETRYABLE_ERROR` o `FINAL_ERROR`. Si el banco hace timeout, el retiro se queda en
`PROCESSING_TRANSFER` hasta que el poller de reconciliación le pregunta al banco qué pasó realmente (grace
period de 30 s, chequeado cada 5 s). Solo `RETRYABLE_ERROR` necesita un operador de nuevo — la reserva
sigue tomada, así que el mismo retiro puede volver a `AUTHORIZED` para otro intento con la misma
idempotency key:

```bash
curl -i -X POST http://localhost:8080/api/withdrawals/<withdrawal-id>/retry \
  -H "X-Operator-Id: operator-1"
```

### Forzar desenlaces de los mocks

Los mocks de riesgo y banco son aleatorios por defecto (latencia y tasas de falla realistas), pero se puede
forzar un desenlace específico desde los **últimos dos decimales del monto**, así los escenarios se pueden
reproducir a mano sin tocar código.

`RiskServiceMock` (1–3 s de latencia, si no ~15% de fallas y niveles ponderados):

| Monto termina en | Desenlace |
| --- | --- |
| `.96` | riesgo `LOW` |
| `.97` | riesgo `MEDIUM` |
| `.98` | la evaluación de riesgo tira una excepción (camino fail-safe → `PENDING_AUTHORIZATION`) |
| `.99` | riesgo `HIGH` → `PENDING_AUTHORIZATION` |

`BankServiceMock` (3–10 s de latencia, si no ~5% error interno / ~3% timeout / ~2% cuenta inválida):

| Monto termina en | Desenlace |
| --- | --- |
| `.13` | error interno (no se cachea por idempotency key — un reintento puede tener éxito) |
| `.14` | timeout donde la transferencia **sí** se aplicó del lado del banco |
| `.15` | timeout donde la transferencia **no** se aplicó |
| `.16` | cuenta de destino inválida (terminal, cacheado por idempotency key) |
| `.50` | éxito garantizado (útil para reintentar de forma determinística después de una falla forzada) |

Entonces `1500.99` fuerza un retiro de riesgo alto que cae en autorización manual, y `1500.50` fuerza una
transferencia bancaria limpia una vez que ese retiro llega a `AUTHORIZED`. Las dos tablas leen los mismos
dos dígitos, así que un monto solo puede forzar una de las dos piernas — la otra queda aleatoria. Los tests
automatizados usan `TestRiskService`/`TestBankService` (instantáneos y totalmente controlables) bajo el
perfil `test`, en vez de estos mocks.

### Una corrida completa, de punta a punta, hasta `EXECUTED`

`.96` fuerza riesgo bajo, que se salta la autorización manual por completo, así que un solo POST alcanza
para ver un retiro llegar hasta el final sin llamar nada más:

```bash
# 1. crearlo — riesgo LOW forzado
curl -s -X POST http://localhost:8080/api/withdrawals \
  -H "Content-Type: application/json" \
  -d '{
    "accountId": "11111111-1111-1111-1111-111111111111",
    "destinationCbu": "0170099220000067797151",
    "amount": 2500.96
  }'

# 2. ~1-3s después el poller de riesgo ya lo evaluó: riesgo LOW, así que ya está AUTHORIZED
curl -s http://localhost:8080/api/withdrawals/<withdrawal-id>

# 3. no hace falta ninguna llamada más — el poller de transferencia toma las filas AUTHORIZED en
#    su próximo tick, así que el mismo GET va a mostrar PROCESSING_TRANSFER y después, tras los
#    3-10s del banco, EXECUTED con la referencia bancaria en "transfer"
curl -s http://localhost:8080/api/withdrawals/<withdrawal-id>
```

Acá la pierna del banco es la aleatoria (`96` no es un dígito de forzado del banco), así que más o menos
nueve de cada diez corridas terminan en `EXECUTED`; el resto ejercita los caminos de error y timeout, que
es justo el punto de dejarlo aleatorio. Para forzar un desenlace de banco específico en cambio, creá el
retiro con un monto `.13`/`.14`/`.15`/`.16` — ahí el riesgo queda como la pierna aleatoria, así que
autorizalo a mano si cae en `PENDING_AUTHORIZATION`.

## Endpoints disponibles hoy

| Método | Path | Notas |
| --- | --- | --- |
| `POST` | `/api/withdrawals` | Reserva el saldo de forma atómica y crea el retiro en `EVALUATING_RISK`. `409 INSUFFICIENT_BALANCE`, `404 NOT_FOUND`, `400 VALIDATION_ERROR`. |
| `GET` | `/api/withdrawals` | Búsqueda paginada para la grilla del backoffice. Ver los query params abajo. |
| `GET` | `/api/withdrawals/{id}` | Detalle completo más el intento de transferencia (estado, referencia bancaria, cantidad de intentos, último error, resuelto en) cuando existe uno, y el historial de auditoría completo (`history`: estado previo/nuevo, actor, timestamp de cada transición). `404 NOT_FOUND`. |
| `POST` | `/api/withdrawals/{id}/authorize` | Requiere el header `X-Operator-Id`. Solo válido desde `PENDING_AUTHORIZATION`. |
| `POST` | `/api/withdrawals/{id}/reject` | Requiere el header `X-Operator-Id`. Libera el saldo reservado en la misma transacción. |
| `POST` | `/api/withdrawals/{id}/retry` | Requiere el header `X-Operator-Id`. Solo válido desde `RETRYABLE_ERROR`; lo devuelve a `AUTHORIZED` para que el poller de transferencia reintente con la misma idempotency key (la reserva nunca se liberó). |
| `POST` | `/api/withdrawals/{id}/resolve-manual-review` | Requiere el header `X-Operator-Id`. Solo válido desde `MANUAL_REVIEW` (la reconciliación se rindió preguntándole al banco); lo cierra a `FINAL_ERROR` y libera el saldo reservado. |
| `GET` | `/api/accounts` | Cuentas sembradas con balance y saldo reservado. Comodidad para testing manual y el selector de cuentas de la UI, no es parte del dominio evaluado. |

Query params de `GET /api/withdrawals`, todos opcionales y combinables:

| Param | Significado |
| --- | --- |
| `status` | Match exacto contra un `WithdrawalStatus` (ej. `PENDING_AUTHORIZATION`). |
| `dateFrom` / `dateTo` | Instantes ISO-8601 (`2026-08-10T00:00:00Z`), inclusive, matcheados contra `createdAt`. |
| `search` | Match parcial sobre el CBU de destino, o un id de cuenta exacto cuando el término parsea como UUID. |
| `page` / `size` / `sort` | Paginación estándar de Spring. Defaults: `size=20`, `sort=createdAt` ascendente; ej. `sort=createdAt,desc`. |

Las cuatro acciones de operador devuelven `409` cuando el retiro ya no está en el estado esperado
(`INVALID_TRANSITION`) o cuando dos operadores compiten por el mismo retiro y el locking optimista rechaza
al perdedor (`CONFLICT`); el cuerpo de la respuesta incluye el estado real actual y quién lo actualizó.

## Decisiones de diseño

El razonamiento detrás del modelo de datos, la estrategia de concurrencia (reserva atómica, pollers con
`SKIP LOCKED`, locking optimista) y los trade-offs tomados está en [`DECISIONS.md`](DECISIONS.md) — este
archivo solo cubre cómo correr el proyecto.

## Estado del proyecto

En desarrollo activo. El flujo de backend está completo de punta a punta y corre de verdad bajo
`docker compose up` con dos instancias: un retiro va desde su creación, pasando por evaluación de riesgo,
autorización manual cuando hace falta, transferencia bancaria y reconciliación de timeouts ambiguos, hasta
`EXECUTED` (o un error terminal/reintentable), sin ningún paso manual más allá de las decisiones del
operador. Construido hasta ahora: modelo de datos y migraciones, mocks de servicios externos (con su verdad
de fondo de idempotencia persistida en Postgres, compartida entre instancias — ver `DECISIONS.md` sección
8), reserva atómica de saldo y creación de retiros, los tres pollers con `SKIP LOCKED` (evaluación de riesgo
con su fail-safe, ejecución de transferencia con su split claim/call/apply y timeout del lado del cliente,
reconciliación con su grace period), autorizar/rechazar/reintentar/resolver revisión manual con locking
optimista, los endpoints de consulta que sostienen la grilla del backoffice, y el `docker-compose.yml` de
dos instancias.

El frontend (`frontend/src/`) está conectado a la API real: una grilla filtrable y paginada, un panel de
detalle con el historial de auditoría completo, las acciones de operador (autorizar/rechazar/reintentar/
resolver revisión manual, bloqueadas detrás de un header de id de operador), un formulario de alta de
retiro con validación del lado del cliente que coincide con la del backend, polling para que las
transiciones que dispara el background aparezcan sin refrescar a mano, y manejo explícito de conflictos 409
(C4) mostrando quién lo resolvió primero en vez de un error genérico. Verificado contra el stack real en un
navegador (capturas + un recorrido de clicks scripteado), no solo contra `curl`. Una simplificación
deliberada: los cambios de estado se consultan por polling (`refetchInterval`, cada 4s) en vez de empujarse
por SSE/WebSockets — la diferencia es de UX/eficiencia, no de correctitud (el dato nunca queda desactualizado
por más de un intervalo de polling, y cada mutación además invalida las queries relevantes al toque), y el
polling no agrega una conexión persistente que mantener entre las dos instancias del backend.
