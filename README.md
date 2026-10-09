# CheckMyKnowledge

**Propiedad intelectual exclusiva de PRISMAL MESH.** Todos los derechos reservados. Marca: `PRISMAL MESH` (dos palabras, mayúsculas, separadas por un espacio).

App Android de exámenes de opción múltiple para alumnos, con reglas anti-trampa.
El profesor administra materias (exámenes), secciones, preguntas, alumnos, asignaciones y horarios de acceso, y ve resultados desde un panel web (`admin.html`).

## Cómo está armado

| Archivo | Qué es |
|---|---|
| `src/main/java/.../MainActivity.java` | Cascarón nativo: abre un WebView con la app y hace cumplir las reglas anti-trampa. |
| `src/main/assets/index.html` | La app del alumno (HTML + JS puro, sin librerías). Habla con Supabase. |
| `admin.html` | Panel del profesor (login, preguntas, secciones, materias, asignaciones, alumnos, importar examen por script, resultados, intentos). Se abre en un navegador; no va dentro del APK. |
| `src/main/AndroidManifest.xml` | Permisos y bloqueo de orientación / multiventana. |
| `build.gradle.kts`, `settings.gradle.kts` | Compilación Gradle. |
| `.github/workflows/build.yml` | Compila el APK en GitHub Actions. |

Backend: Supabase.
- Tablas: `materias`, `preguntas`, `resultados`, `alumnos`, `intentos`, `asignaciones`.
- Funciones RPC: `validar_matricula`, `iniciar_intento`, `calificar_examen`, `materias_disponibles`.
- Funciones internas (no se llaman desde el cliente): `_verificar_firma`, `_motivo_no_disponible`, `_max_reanudaciones`, `duracion_examen`, `_cerrar_intentos_vencidos` (la ejecuta `pg_cron` cada minuto; ver "Cierre automático de intentos vencidos").

## Exámenes: activar, asignar y programar acceso (7/oct/2026)

En la base de datos, un "examen" es una fila de `materias`. Quién puede **iniciarlo** y cuándo lo decide el **servidor**, nunca el cliente.

### Reglas de acceso

`iniciar_intento` llama a `_motivo_no_disponible(matrícula, materia)`, que devuelve `null` (puede entrar) o el motivo:

| Motivo | Cuándo |
|---|---|
| `desactivado` | `materias.activa = false` o la materia no existe |
| `no_asignado` | `materias.solo_asignados = true` y el alumno no tiene fila en `asignaciones` |
| `aun_no_abre` | ahora < hora de apertura |
| `cerrado` | ahora ≥ hora de cierre |

- **Ventana efectiva** = la de la asignación del alumno si la tiene; si no, la de la materia (`coalesce(asignaciones.abre_en, materias.abre_en)`, igual para `cierra_en`). Fecha vacía = sin límite.
- **Quien ya tiene un intento en curso lo puede terminar** aunque la ventana haya cerrado (no se le corta a media prueba). `calificar_examen` no revisa la ventana a propósito.
- Un intento ya usado con la ventana cerrada responde "no disponible" en vez de "usado" (el efecto para el alumno es el mismo: no puede entrar).
- Si el servidor rechaza, el error es `Examen no disponible (motivo)`.
- Con `solo_asignados = true` y cero asignados, el examen queda cerrado para todos.
- Los horarios usan el reloj del **servidor** (`now()`); el panel muestra y captura la hora del equipo del profesor y la convierte a UTC al guardar.

### Tablas y funciones nuevas

- `materias`: columnas `abre_en timestamptz`, `cierra_en timestamptz`, `solo_asignados boolean default false`. Sin datos en ellas el examen se comporta como antes.
- `asignaciones(id, matricula → alumnos, materia_id → materias, abre_en, cierra_en, creado_en)` con `UNIQUE(matricula, materia_id)`. Se borran en cascada con el alumno o la materia. RLS: solo el admin (correo del profesor) lee/escribe; `anon` no tiene acceso.
- `materias_disponibles(p_matricula)` → `[{id, nombre}]` con las materias que ESE alumno puede abrir ahora. Es lo que debe usar la app del alumno (ver pendiente abajo). No expone asignaciones de otros alumnos.
- Migración aplicada en Supabase: `asignaciones_y_ventanas_de_acceso`.

### ⚠ Pendiente: `index.html` (app del alumno)

Hoy la app sigue listando las materias con la política pública (`materias` con `activa = true`) **antes** de pedir la matrícula. El servidor ya rechaza lo que no corresponde, pero el alumno vería materias que no son suyas y fallaría al entrar. Para terminarlo:
1. Pedir la matrícula primero (validar con `validar_matricula`).
2. Mostrar la lista que devuelve `materias_disponibles(matrícula)` en lugar de leer `materias` directamente.
3. Mostrar un mensaje claro cuando `iniciar_intento` responda `Examen no disponible (...)`.

Cuando eso esté hecho, valorar quitar la política pública `cualquiera puede leer materias activas` para que `anon` no pueda listar nombres de exámenes restringidos.

## Acceso del alumno (`index.html` + Supabase)

- **Solo con matrícula.** El alumno escribe su matrícula; la app llama a `validar_matricula`. Si existe y está activa, entra; si no, ve "Matrícula no registrada" (sin pistas de matrículas parecidas).
- **Encabezado del examen:** barra fija arriba con el reloj y, debajo, `nombre · matrícula · Grupo`, tal como los devuelve el servidor.
- **La validación es del lado del servidor.** Tabla `alumnos(matricula PK, nombre, grupo, activo, creado_en)` con RLS: `anon` no puede leerla ni escribirla, solo el admin desde el panel. La lista completa nunca viaja a la app.
- **Intentos (`intentos`):** un intento por `(matrícula, materia)`, creado por `iniciar_intento`. El servidor lleva el inicio, las reanudaciones (veces que el alumno volvió a un intento con preguntas ya entregadas) y el fin. Al pasar el máximo de reanudaciones (`_max_reanudaciones`, editable solo por SQL) el intento se anula con 0; si se acaba la duración (`duracion_examen()`) se cierra como `agotado`: lo hace `iniciar_intento` si el alumno vuelve, o el cron `cerrar-intentos-vencidos` si no vuelve (ver abajo).
- **Peticiones firmadas:** `iniciar_intento` y `calificar_examen` reciben `p_ts` y `p_firma` y las validan con `_verificar_firma`. Si cambias esa firma, cambia también el cliente.
- **`calificar_examen`** valida la matrícula y el intento, califica en el servidor y guarda el resultado (nombre y grupo salen de la tabla `alumnos`, no de lo que mande el cliente). Acepta entregas hasta **120 s** después de vencer el tiempo; el cron espera 150 s (ver regla 16).
- **Resultados:** `resultados` incluye `matricula`, `grupo`, `reanudaciones` y `motivo` (`anulado` / `agotado` cuando el servidor lo cerró en 0).
- **Alumnos:** se administran desde la pestaña Alumnos del panel (alta, edición, activar/desactivar, borrar e importar CSV `matricula,nombre,grupo`). `activo = false` impide entrar sin borrar historial.
- **Límite conocido:** al ser acceso solo por matrícula, quien conozca la de un compañero puede entrar como él, y puede probar matrículas (suelen ser predecibles). Las asignaciones por examen ayudan a acotar el daño, pero no son un segundo factor. Si hace falta: PIN por alumno.

## Cierre automático de intentos vencidos (8/oct/2026, `pg_cron`)

**Problema:** el servidor solo cerraba por tiempo un intento cuando el alumno volvía a llamar a `iniciar_intento`. Si la app se le cerró (la anulación no avisa al servidor) y no volvió, o se quedó sin red al entregar, su intento quedaba **"En curso" para siempre** en la pestaña Intentos (se vio uno de las 9:06 a.m. abierto horas después) y **no aparecía en Resultados**.

**Solución (solo en Supabase; el panel y la app no cambian):**
- La extensión `pg_cron` ejecuta cada minuto el job `cerrar-intentos-vencidos`, que llama a `public._cerrar_intentos_vencidos()`.
- Cierra los intentos con `fin is null` y `now() >= inicio + duracion_examen() + 150 s`: inserta un resultado con **0 correctas**, `respuestas = []` y `motivo = 'agotado'` (igual que `iniciar_intento`), y pone `intentos.fin = inicio + duración`.
- El margen de 150 s es mayor que los 120 s de `calificar_examen` para no quitarle el examen a quien entrega justo al final. Si el intento está siendo cerrado por `calificar_examen`, se salta (`skip locked`); si el resultado ya existe no se duplica (`on conflict do nothing`).
- Un intento abandonado pasa solo a **"Tiempo agotado"** (pestañas Intentos y Resultados, Motivo `agotado`) unos **63 min** después de su inicio. Hasta entonces se ve "En curso" aunque el alumno ya no esté contestando.
- Función interna: `EXECUTE` revocado para `public`, `anon` y `authenticated`. Migración: `cerrar_intentos_vencidos_con_cron`.

**Qué hacer en el panel:** si un alumno quedó en 0 por `agotado` y debe repetir, usa **Liberar** en la pestaña Intentos (borra intento y resultado; podrá presentar de nuevo con 0 salidas). Sus respuestas anteriores no se pueden recuperar (solo vivían en el WebView).

**Verificar que sigue activo (SQL Editor de Supabase, solo lectura):**
```sql
select jobname, schedule, active from cron.job where jobname = 'cerrar-intentos-vencidos';
select matricula, materia_id, inicio from public.intentos
where fin is null and now() >= inicio + public.duracion_examen() + interval '3 minutes';  -- debe salir vacío
```
Apagarlo: `select cron.unschedule('cerrar-intentos-vencidos');` (los intentos vuelven a quedar abiertos hasta que el alumno regrese). Si ves en Intentos un "En curso" con más de ~63 min, el cron no está corriendo.

## Cómo ve el examen el alumno (`index.html`)

- **Una sección por pantalla, con todas sus preguntas juntas.** Las preguntas se agrupan por el texto de `preguntas.seccion`: cada vez que cambia respecto a la pregunta anterior (en orden) empieza una sección nueva. Las preguntas seguidas sin sección forman un solo grupo sin título. Botones **← Sección anterior** / **Siguiente sección →**; el alumno puede volver a corregir respuestas.
- **El contexto (video/texto) se muestra una sola vez** cuando varias preguntas seguidas de la misma sección lo comparten; reaparece si cambia.
- **Temporizador** con barra fija arriba; se pone roja en los últimos 5 min. La duración real la manda el servidor (`restante_seg`); al llegar a 0 el examen se entrega solo con lo contestado.
- **Preguntas sin responder** se envían como `-1` en `p_respuestas` (nunca `null`), así el arreglo siempre lleva un número por pregunta y `-1` cuenta como mala.
- En la última sección, si faltan respuestas se avisa una vez y el segundo toque en **Terminar de todos modos** entrega. No se usa `confirm()` en el alumno: en el WebView no funciona y robaría el foco (anularía el examen).

## Reglas anti-trampa (en `MainActivity.java`)

Mientras el examen está activo, la app se **cierra por completo** si:

- pierde el foco (notificaciones, ventanas flotantes),
- va a Inicio, Recientes o cambia de app,
- el sensor de proximidad queda tapado 800 ms seguidos (celular pegado a la pantalla).

Al reabrir, el servidor decide si es una reanudación permitida o si el intento se anula (ver Intentos). Si el alumno no vuelve, el cron cierra su intento como `agotado` con 0 pasada la duración. Además: capturas y grabación bloqueadas (`FLAG_SECURE`), botón Atrás desactivado, sin orientación horizontal ni multiventana, sin navegación a otros sitios.

### Sensor de proximidad: detalles y límites

- Se considera "cerca" una lectura menor a `min(rangoMáximoDelSensor, 5 cm)` (`DISTANCIA_CERCA_CM`).
- Solo detecta algo pegado al **borde superior** de la pantalla (donde está el sensor). Un segundo celular al lado o más abajo **no** se detecta: es una defensa parcial.
- Si el equipo no tiene sensor, la defensa no se activa en ese equipo.
- **Estado (2/oct/2026): en pruebas reales sigue sin anular el examen; investigación en pausa.** Para diagnosticar existe `DEPURAR_SENSOR`.

### Modo depuración del sensor (`DEPURAR_SENSOR`)

Con `DEPURAR_SENSOR = true` la app muestra avisos (Toast) con el sensor, su rango y cada lectura (`valor`, `umbral`, `cerca`, `examenActivo`). Los Toast no roban el foco, así que no anulan el examen.

| Qué ves al tapar el sensor con el examen empezado | Causa |
|---|---|
| Ninguna lectura | El sensor no entrega eventos o no se tapa el sensor correcto |
| `cerca=true examenActivo=false` | Falla el puente JS (`iniciarExamen` no llega) |
| `cerca=true examenActivo=true` y no se cierra | Bug en el temporizador |

**⚠ Antes de repartir el APK a los alumnos, poner `DEPURAR_SENSOR = false`.**

## Panel del profesor (`admin.html`)

Pestañas: **Preguntas · Secciones · Materias · Asignaciones · Alumnos · Importar · Resultados · Intentos**.

### Materias (exámenes)
- Crear (nacen **desactivadas**), renombrar, ordenar, **activar/desactivar** y borrar. Borrar puede ser rechazado por la base si hay preguntas, resultados o intentos ligados; para ocultar sin borrar, desactivar.
- **Horario de acceso:** "Abre el" y "Cierra el" (fecha y hora). Atajos: **Cerrar acceso ahora** y **Quitar horario**. Valida que el cierre sea posterior a la apertura.
- **Solo alumnos asignados:** restringe el examen a quienes estén en la pestaña Asignaciones.
- Cada tarjeta muestra el estado calculado: Desactivado / Abre … / Abierto hasta … / Cerrado (informativo; el servidor decide).

### Asignaciones
- Se elige el examen; se busca por matrícula o nombre, se filtra por grupo y se marcan alumnos (o "todos los mostrados"). **Asignar seleccionados** hace upsert por `(matrícula, materia)`.
- **Ventana propia opcional** (abre/cierra) para los asignados; tiene prioridad sobre la de la materia. Si se deja vacía, hereda la de la materia. Reasignar a alguien ya asignado actualiza su ventana.
- Lista de asignados con sus fechas y **Quitar seleccionados**.
- Si el examen sigue abierto a todos, avisa y ofrece **Restringir a alumnos asignados**.

### Preguntas
- Filtro por materia. Casilla por pregunta y "seleccionar todas las mostradas". Con las marcadas: **Asignar sección**, **Quitar sección** (sección vacía; no borra preguntas) y **Borrar seleccionadas**.
- **Borrar todas las mostradas**: borra lo que muestre el filtro actual (sin filtro = todo).
- Cada pregunta muestra su sección como etiqueta.

### Secciones
Las secciones **no son una tabla**: son texto libre en `preguntas.seccion`. *Crear* = poner ese texto a las preguntas de una materia por rango de `orden`. *Renombrar* cambia el texto en todas sus preguntas. *Quitar* vacía el texto (las preguntas se conservan). *Borrar con preguntas* las elimina.
- ⚠ Como el alumno ve **una sección por pantalla**, las preguntas de una sección deben tener `orden` consecutivo; si se intercalan con otra sección, se partirán en varias pantallas.

### Alumnos
Lista con búsqueda y filtro por grupo; alta/edición (la matrícula no se edita), activar/desactivar, borrar (borra también sus asignaciones; los resultados se conservan) e importar CSV (coma, punto y coma o tabulador; con o sin encabezado; upsert por matrícula).

### Resultados e Intentos
- **Resultados:** alumno, matrícula, grupo, materia, puntaje, **Salidas** (reanudaciones) y **Motivo**. Borrar un resultado también borra su intento.
- **Intentos:** en curso y cerrados, con su contador de salidas y botón **Liberar** (borra intento y resultado: el alumno puede presentar de nuevo con 0 salidas). **"En curso" solo significa que el intento no tiene `fin`**: un alumno que abandonó el examen aparece así hasta ~63 min después de su inicio, cuando el cron lo pasa a "Tiempo agotado" (ver "Cierre automático de intentos vencidos").
- Ambas pestañas se recargan al abrirlas.

### Importar por script (Apps Script de Forms)
Se pega un script casi tal cual de Google Forms; **Limpiar y corregir** arregla bloques ```, espacios invisibles y comillas tipográficas, y **Procesar** ejecuta el script en el navegador (no se envía a ningún lado) y muestra una vista previa antes de guardar.

| Llamada | Efecto |
|---|---|
| `FormApp.create('Título')` | El título es la **sección** de sus preguntas (varios `create` se importan en orden) |
| `addMultipleChoiceItem()` / `createChoice(valor, esCorrecta)` | Pregunta de opción múltiple |
| `addSectionHeaderItem()` / `addPageBreakItem()` + `setTitle` | Cambia la **sección** de las preguntas siguientes |
| `…setHelpText('texto o link')` (en el encabezado) | Es el **contexto** de las preguntas siguientes (un link de YouTube se reproduce incrustado; si es texto, se muestra como bloque de lectura) |
| `addVideoItem().setVideoUrl(url)` | Esa URL es el contexto de las preguntas siguientes |
| `addTextItem` / `addListItem` | Se ignoran (los datos del alumno los pide la app) |
| Otras llamadas de Forms / `Logger` / `Utilities`… | Se ignoran y se avisa cuáles |

Solo se ejecutan solas las funciones **sin parámetros** que nadie más llama (si no, se duplicarían preguntas). Se quita la numeración del título ("1. ¿…?") porque la app ya numera. "Confirmar" se bloquea si no hay preguntas, para que "Reemplazar" no deje la materia vacía.

### Videos de YouTube dentro de la app
Una página `file://` no tiene origen válido y YouTube responde "error de configuración del reproductor". Por eso `MainActivity` carga `index.html` con `loadDataWithBaseURL(origenBase(), ...)` (origen `https://<applicationId>`) y activa DOM storage. Si el error persiste, cambiar solo `origenBase()`. Un video con embebido deshabilitado por su dueño tampoco carga: probar con otro.

### Permisos (RLS) y errores
- Todas las escrituras del panel que deben afectar filas usan `escribirConfirmado()` (alias `escribirAlumnos()`): piden `return=representation` y avisan si la base no aplicó el cambio (RLS o registro inexistente) en lugar de fingir éxito. No usarla donde 0 filas sea válido.
- Sesión vencida (401): se muestra el login con el aviso; ninguna pantalla se redibuja encima.
- Las políticas "admin puede …" se basan en el correo del profesor (`auth.jwt() ->> 'email'`). Cada tabla nueva del panel necesita sus políticas (ya existen para `materias`, `alumnos`, `intentos` y `asignaciones`).

## Reglas para quien modifique el código (persona o IA)

1. **Comenta el código.** Los comentarios son obligatorios: explican el *porqué*, no solo el qué, para que cualquiera pueda retomarlo.
2. **Entrega archivos completos y listos para descargar**, no fragmentos.
3. **Actualiza este README** en cada cambio relevante (también cuando el cambio sea solo en la base de datos).
4. **Nunca se envía la respuesta correcta al cliente.** `index.html` solo pide `texto, opciones, contexto, seccion`; la calificación la hace `calificar_examen` en Supabase. No agregar `correcta` al `select` del cliente.
5. **Cualquier regla anti-trampa nueva debe terminar en `anularExamen()`** de `MainActivity.java`.
6. **Los links externos romperían el examen**: abrir otra app (p. ej. YouTube) hace que la app pierda el foco y se anule. Los videos van *embebidos* (iframe), nunca como link.
7. **El puente JS↔Java** (`Puente`, `window.Android`) tiene tres métodos: `iniciarExamen`, `terminarExamen`, `salir`. Si cambias nombres, cámbialos en `index.html` también.
8. **No volver a agregar pasos al workflow que hagan commit/push** al repo (ver nota en `build.yml`).
9. La clave `anon` de Supabase en `index.html` y `admin.html` es pública por diseño; la seguridad real está en las políticas RLS.
10. **`DEPURAR_SENSOR` debe estar en `false`** en cualquier APK que se reparta a alumnos.
11. **No usar `alert()`/`confirm()`/`prompt()` en `index.html`**: abren un diálogo nativo que roba el foco y anula el examen. (En `admin.html` sí se pueden usar.)
12. **La lista de alumnos nunca va en el cliente del alumno.** Vive en `alumnos` (RLS cerrada para `anon`); la app solo usa RPC. No dar permisos de lectura a `anon` sobre `alumnos` ni `asignaciones`.
13. **Identidad de marca:** todo comentario, documento o variable que nombre a la empresa usa `PRISMAL MESH` (dos palabras separadas por un espacio). Los archivos nuevos llevan en el encabezado: `Propiedad intelectual de PRISMAL MESH. Todos los derechos reservados.`
14. **El acceso a exámenes (activo, asignado, horario) se decide SIEMPRE en el servidor** (`iniciar_intento` → `_motivo_no_disponible`). El cliente solo lo muestra; nunca confiar en ocultar botones o en la hora del dispositivo.
15. **Si cambias la firma de `iniciar_intento` o `calificar_examen`**, actualiza `index.html` (parámetros `p_ts` y `p_firma`) y este README.
16. **Un intento vencido nunca debe quedar abierto, y el cron nunca debe adelantarse a una entrega legítima.** El job `cerrar-intentos-vencidos` debe seguir activo y esperar **más** que el margen de `calificar_examen` (hoy 120 s; el cron usa 150 s). Si cambias ese margen, la duración (`duracion_examen()`) o las columnas de `intentos`/`resultados`, revisa `_cerrar_intentos_vencidos` y conserva su `EXECUTE` revocado para `anon`/`authenticated`.

## Compilar

Push a `main` (o "Run workflow" en la pestaña Actions). El APK queda como artifact `checkmyknowledge-apk`. `admin.html` no se compila: se sube/abre aparte.

## Historial de cambios

- **8/oct/2026** — **Cierre automático de intentos vencidos.** Intentos de alumnos que no volvieron quedaban "En curso" horas después de vencer y sin resultado. Supabase (migración `cerrar_intentos_vencidos_con_cron`): `pg_cron` + función interna `_cerrar_intentos_vencidos()` + job `cerrar-intentos-vencidos` cada minuto; cierra con 0 y `motivo = 'agotado'` pasados `duracion + 150 s`. Probado cerrando el intento abandonado de la matrícula 24324033. Sin cambios en `admin.html`, `index.html` ni el APK. Nueva sección y regla 16.
- **7/oct/2026** — Asignación de exámenes a alumnos específicos, activar/desactivar exámenes y horario de acceso (abre/cierra) con ventana propia por alumno. Migración `asignaciones_y_ventanas_de_acceso` (columnas en `materias`, tabla `asignaciones`, `_motivo_no_disponible`, `materias_disponibles`, verificación en `iniciar_intento`). `admin.html`: pestañas **Materias** y **Asignaciones**. Pendiente: `index.html` debe usar `materias_disponibles`.
- **5/oct/2026** — `admin.html`: pestañas Secciones, Alumnos (con CSV) e Intentos; selección y borrado masivo de preguntas; borrados confirmados por filas afectadas; manejo de sesión vencida; importador con limpieza/corrección automática, videos y texto de apoyo; Resultados con Salidas y Motivo. Servidor: intentos, reanudaciones y peticiones firmadas.
- **2/oct/2026 (6)** — Identidad PRISMAL MESH en encabezados y documentación (regla 13).
- **2/oct/2026 (5)** — Acceso por **matrícula** validada en Supabase: tabla `alumnos` (RLS cerrada), `validar_matricula`, `calificar_examen` por matrícula, columnas `matricula` y `grupo` en `resultados`.
- **2/oct/2026 (4)** — `index.html`: una sección completa por pantalla, contexto mostrado una vez por bloque, temporizador con entrega automática, preguntas sin responder como `-1`.
- **2/oct/2026 (3)** — `MainActivity`: `index.html` con origen https y DOM storage, para corregir el error del reproductor de YouTube.
- **2/oct/2026 (2)** — Importador: corregido `Logger is not defined`; solo ejecuta funciones sin parámetros; soporta `addPageBreakItem` y links de YouTube en `setHelpText`.
- **2/oct/2026** — `admin.html`: pestañas Materias y Secciones, borrado masivo, video y encabezados de sección en el importador, detección de operaciones bloqueadas por RLS.
- **1/oct/2026** — Modo depuración del sensor (`DEPURAR_SENSOR`), umbral de "cerca" a `min(rangoMax, 5 cm)`, reglas 1–3 y 10 del README.
