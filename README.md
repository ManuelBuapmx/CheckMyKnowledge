# CheckMyKnowledge

**Propiedad intelectual exclusiva de PRISMAL MESH.** Todos los derechos reservados. Marca: `PRISMAL MESH` (dos palabras, mayúsculas, separadas por un espacio).

App Android de exámenes de opción múltiple para alumnos, con reglas anti-trampa.
El profesor administra materias, secciones y preguntas, y ve resultados desde un panel web (`admin.html`, repo aparte **CMKAdmin**).

## Cómo está armado

| Archivo | Qué es |
|---|---|
| `src/main/java/.../MainActivity.java` | Cascarón nativo: abre un WebView con la app y hace cumplir las reglas anti-trampa. |
| `src/main/assets/index.html` | La app del alumno (HTML + JS puro, sin librerías). Habla con Supabase. |
| `admin.html` | **Ya no está en este repo:** vive en `ManuelBuapmx/CMKAdmin` (login, preguntas, alumnos, importar examen por script, resultados). Se abre en un navegador; no va dentro del APK. Ver el README de ese repo. |
| `src/main/AndroidManifest.xml` | Permisos y bloqueo de orientación / multiventana. |
| `build.gradle.kts`, `settings.gradle.kts` | Compilación Gradle. |
| `.github/workflows/build.yml` | Compila el APK en GitHub Actions. |

Backend: Supabase (tablas `materias`, `preguntas`, `resultados`, `alumnos`; funciones RPC `validar_matricula` y `calificar_examen`).

## Acceso del alumno (`index.html` + Supabase)

- **Solo con matrícula.** El alumno escribe su matrícula; la app llama a la función `validar_matricula` de Supabase. Si existe y está activa, entra; si no, ve "Matrícula no registrada" (sin pistas de matrículas parecidas).
- **Encabezado del examen:** barra fija arriba con el reloj y, debajo, `nombre · matrícula · Grupo`, tal como los devuelve el servidor.
- **La validación es del lado del servidor.** Tabla `alumnos(matricula PK, nombre, grupo, activo, creado_en)` con RLS: el rol `anon` no puede leerla ni escribirla (privilegios revocados), solo el admin desde el panel. La lista completa nunca viaja a la app.
- **Funciones RPC:**
  - `validar_matricula(p_matricula)` → `{matricula, nombre, grupo}` o error `Matrícula no registrada`.
  - `calificar_examen(p_materia_id, p_matricula, p_respuestas)` → valida la matrícula OTRA VEZ, toma nombre y grupo de la tabla y guarda el resultado. **Ya no acepta un nombre libre** (la versión anterior `calificar_examen(bigint, text-nombre, jsonb)` se eliminó para que no sirva de puerta trasera: un APK anterior ya no puede calificar).
- **Resultados:** `resultados` tiene columnas nuevas `matricula` y `grupo`; `nombre` guarda solo el nombre.
- **Alumnos:** se administran desde la pestaña **Alumnos** del panel (CMKAdmin): alta, edición, activar/desactivar, borrar e importación por CSV con columnas `matricula,nombre,grupo`. También se pueden editar en el Table Editor de Supabase. `activo = false` impide entrar sin borrar historial. Alumno de prueba cargado: `123456 · ROBLES GONZÁLEZ JOSÉ MANUEL · 10B`.
- **Límite conocido:** al ser acceso solo por matrícula, quien conozca la matrícula de un compañero puede entrar como él, y puede probar matrículas (suelen ser predecibles). No hay otro factor. Si hace falta, añadir un solo intento por matrícula/materia o un PIN por alumno.

## Cómo ve el examen el alumno (`index.html`)

- **Una sección por pantalla, con todas sus preguntas juntas.** Las preguntas se agrupan por el texto de `preguntas.seccion`: cada vez que cambia respecto a la pregunta anterior (en orden) empieza una sección nueva. Las preguntas seguidas sin sección forman un solo grupo sin título. Botones **← Sección anterior** / **Siguiente sección →**; el alumno puede volver a corregir respuestas.
- **El contexto (video/texto) se muestra una sola vez** cuando varias preguntas seguidas de la misma sección lo comparten; reaparece si cambia. (Antes se repetía en cada pregunta porque había una por pantalla.)
- **Temporizador de 1 hora** (`DURACION_MIN = 60` al inicio del `<script>`). Barra fija arriba con la cuenta regresiva; se pone roja en los últimos 5 min. Se calcula con una hora de fin (`Date.now()`), no restando 1 por tick. Al llegar a 0 el examen **se entrega solo** con lo contestado.
- **Preguntas sin responder** se envían como `-1` en `p_respuestas` (nunca `null`), así el arreglo siempre lleva un número por pregunta y `-1` cuenta como mala (verificado: `calificar_examen` solo compara `respuesta = correcta`).
- En la última sección, si faltan respuestas se avisa una vez y el segundo toque en **Terminar de todos modos** entrega. No se usa `confirm()`: en el WebView no funciona y robaría el foco (anularía el examen).
- El reloj corre solo en el cliente: si el alumno cierra la app, el examen se anula y empieza de cero con reloj nuevo (misma regla de siempre).

## Reglas anti-trampa (en `MainActivity.java`)

Mientras el examen está activo, la app se **cierra por completo** y el alumno empieza de cero si:

- pierde el foco (notificaciones, ventanas flotantes),
- va a Inicio, Recientes o cambia de app,
- el sensor de proximidad queda tapado 800 ms seguidos (celular pegado a la pantalla).

Además: capturas y grabación de pantalla bloqueadas (`FLAG_SECURE`), botón Atrás desactivado,
sin orientación horizontal ni multiventana, sin navegación a otros sitios.

### Sensor de proximidad: detalles y límites

- Se considera "cerca" una lectura menor a `min(rangoMáximoDelSensor, 5 cm)` (`DISTANCIA_CERCA_CM`).
- Solo detecta algo pegado al **borde superior** de la pantalla (donde está el sensor). Un segundo celular al lado o más abajo **no** se detecta: es una defensa parcial.
- Si el equipo no tiene sensor, la defensa no se activa en ese equipo.
- **Estado (2/oct/2026): en pruebas reales sigue sin anular el examen; investigación en pausa.** Para diagnosticar existe `DEPURAR_SENSOR`.

### Modo depuración del sensor (`DEPURAR_SENSOR`)

Con `DEPURAR_SENSOR = true` en `MainActivity.java` la app muestra avisos (Toast) con el sensor detectado, su rango y cada lectura (`valor`, `umbral`, `cerca`, `examenActivo`). Los Toast no roban el foco, así que no anulan el examen.

| Qué ves al tapar el sensor con el examen empezado | Causa |
|---|---|
| Ninguna lectura | El sensor no entrega eventos o no se tapa el sensor correcto |
| `cerca=true examenActivo=false` | Falla el puente JS (`iniciarExamen` no llega) |
| `cerca=true examenActivo=true` y no se cierra | Bug en el temporizador |

**⚠ Antes de repartir el APK a los alumnos, poner `DEPURAR_SENSOR = false`.**

## Panel del profesor (repo CMKAdmin)

El panel vive en su propio repo, `ManuelBuapmx/CMKAdmin`, y su README es la referencia de qué hace. Pestañas actuales: **Preguntas · Alumnos · Importar · Resultados**.

- **Preguntas:** alta, edición y borrado con materia, sección y contexto; filtro por materia.
- **Alumnos:** lista con búsqueda y filtro por grupo, alta/edición, activar/desactivar, borrar e importación CSV.
- **Importar:** script estilo Apps Script de Forms (`FormApp.create`, `addMultipleChoiceItem`, `createChoice`...). El título de cada `FormApp.create` es la sección de sus preguntas.
- **Resultados:** alumno, matrícula, grupo, materia, puntaje y fecha.

**Pendiente (descrito en versiones anteriores de este README, pero NO incluido en el panel actual):** pestañas Secciones y Materias, selección y borrado masivo de preguntas, asignar/quitar sección, soporte de `addSectionHeaderItem`/`addVideoItem`/`addPageBreakItem`/`Logger` en el importador, solo ejecutar funciones sin parámetros y detección de operaciones bloqueadas por RLS en preguntas/materias/resultados.

Las secciones siguen siendo texto libre en `preguntas.seccion`. Como el alumno ve **una sección por pantalla**, las preguntas de una sección deben tener `orden` consecutivo; si se intercalan con otra sección, se partirán en varias pantallas.

## Videos de YouTube dentro de la app

Una página `file://` no tiene origen válido y YouTube responde "error de configuración del reproductor" (153). Por eso `MainActivity` carga `index.html` con `loadDataWithBaseURL(origenBase(), ...)` (origen `https://<package de la app>`) y activa DOM storage. El origen debe identificar a **nuestra app**, no a YouTube (con `https://www.youtube.com` el reproductor respondía error 152-4). Si el error persiste, lo único que hay que tocar es la función `origenBase()` (p. ej. devolver `https://localhost/`). Un video con embebido deshabilitado por su dueño tampoco carga: probar con otro.

## Permisos (RLS)

Las políticas de admin sobre `preguntas`, `materias`, `resultados` y `alumnos` están limitadas al correo del profesor (no a cualquier usuario autenticado); `anon` solo lee preguntas y materias activas e inserta resultados vía RPC. El panel debe avisar cuando la base no aplica un cambio; hoy lo hace solo en `alumnos` (ver "Pendiente" arriba).

## Reglas para quien modifique el código (persona o IA)

1. **Comenta el código.** Los comentarios son obligatorios: explican el *porqué*, no solo el qué, para que cualquiera pueda retomarlo.
2. **Entrega archivos completos y listos para descargar**, no fragmentos.
3. **Actualiza este README** en cada cambio relevante.
4. **Nunca se envía la respuesta correcta al cliente.** `index.html` solo pide `texto, opciones, contexto, seccion`; la calificación la hace `calificar_examen` en Supabase. No agregar `correcta` al `select` del cliente.
5. **Cualquier regla anti-trampa nueva debe terminar en `anularExamen()`** de `MainActivity.java`.
6. **Los links externos romperían el examen**: abrir otra app (p. ej. YouTube) hace que la app pierda el foco y se anule. Los videos van *embebidos* (iframe), nunca como link.
7. **El puente JS↔Java** (`Puente`, `window.Android`) tiene tres métodos: `iniciarExamen`, `terminarExamen`, `salir`. Si cambias nombres, cámbialos en `index.html` también.
8. **No volver a agregar pasos al workflow que hagan commit/push** al repo (ver nota en `build.yml`).
9. La clave `anon` de Supabase en `index.html` y `admin.html` es pública por diseño; la seguridad real está en las políticas RLS.
10. **`DEPURAR_SENSOR` debe estar en `false`** en cualquier APK que se reparta a alumnos.
11. **No usar `alert()`/`confirm()`/`prompt()` en `index.html`**: abren un diálogo nativo que roba el foco y anula el examen. Usar mensajes dentro de la página.
12. **La lista de alumnos nunca va en el cliente.** Vive en la tabla `alumnos` (RLS cerrada para `anon`); la app solo usa `validar_matricula` y `calificar_examen`. No dar permisos de lectura a `anon` sobre `alumnos`.
13. **Identidad de marca:** todo comentario, documento o variable que nombre a la empresa usa `PRISMAL MESH` (dos palabras separadas por un espacio). Los archivos nuevos llevan en el encabezado: `Propiedad intelectual de PRISMAL MESH. Todos los derechos reservados.`

## Compilar

Push a `main` (o "Run workflow" en la pestaña Actions). El APK queda como artifact `checkmyknowledge-apk`. `admin.html` ya no vive aquí (repo CMKAdmin); no se compila: se abre aparte en un navegador.

## Historial de cambios

- **2/oct/2026 (7)** — Alumno de prueba actualizado a `123456 · ROBLES GONZÁLEZ JOSÉ MANUEL · 10B` (README y tabla `alumnos` de Supabase). El panel pasó a su propio repo (CMKAdmin): se actualizó este README para describir solo lo que el panel tiene hoy y se movió lo demás a "Pendiente".
- **2/oct/2026 (6)** — Identidad PRISMAL MESH en encabezados y documentación (regla 13).
- **2/oct/2026 (5)** — Acceso por **matrícula** validada en Supabase: tabla `alumnos` (RLS cerrada), funciones `validar_matricula` y `calificar_examen` nueva (recibe matrícula, reemplaza a la anterior), columnas `matricula` y `grupo` en `resultados`. `index.html` muestra nombre, matrícula y grupo en el encabezado fijo del examen.
- **2/oct/2026 (4)** — `index.html`: cada sección se muestra completa en una sola pantalla (todas sus preguntas), con navegación entre secciones; contexto mostrado una vez por bloque; temporizador de 60 min (`DURACION_MIN`) con entrega automática; preguntas sin responder se mandan como `-1`.
- **2/oct/2026 (3)** — `MainActivity`: `index.html` se carga con origen https (`ORIGEN_BASE`) y DOM storage activo, para corregir el error de configuración del reproductor de YouTube.
- **2/oct/2026 (2)** — Importador: corregido `Logger is not defined`; solo ejecuta funciones sin parámetros (antes truena con `addQuestion(...)`); soporta `addPageBreakItem` como sección y links de YouTube dentro de `setHelpText`.
- **2/oct/2026** — `admin.html`: pestañas Materias y Secciones, selección y borrado masivo de preguntas, asignar/quitar secciones, soporte de video de YouTube y encabezados de sección en el script de importación, detección de operaciones bloqueadas por RLS.
- **1/oct/2026** — Modo depuración del sensor (`DEPURAR_SENSOR`), umbral de "cerca" a `min(rangoMax, 5 cm)`, reglas 1–3 y 10 del README.
