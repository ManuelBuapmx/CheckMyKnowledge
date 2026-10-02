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

Backend: Supabase (tablas `materias`, `preguntas`, `resultados`, `alumnos`, `intentos`; funciones RPC `validar_matricula`, `iniciar_intento` y `calificar_examen`).

## Acceso del alumno y intento único (`index.html` + Supabase)

- **Solo con matrícula.** El alumno escribe su matrícula y toca "Comenzar examen"; la app llama a `iniciar_intento` de Supabase. Si la matrícula existe y está activa, entra; si no, ve "Matrícula no registrada" (sin pistas de matrículas parecidas).
- **Un intento por matrícula y por materia.** La tabla `intentos(matricula, materia_id, inicio, fin)` tiene una restricción única `(matricula, materia_id)`. La hora de `inicio` la guarda el **servidor**. Un índice único en `resultados(matricula, materia_id)` es el respaldo: la base nunca acepta dos resultados de la misma matrícula y materia.
- **Cerrar o reiniciar la app NO da otro intento ni detiene el reloj.** El alumno puede volver a entrar con su matrícula y `iniciar_intento` **reanuda** el mismo intento, devolviendo los segundos que le quedan (calculados con el reloj del servidor). Ejemplo: si se le cerró la app a los 30 min, al volver le quedan 30 min menos lo que tardó en reabrirla. Sus **respuestas se pierden** (viven solo en el WebView) y empieza a contestar de nuevo.
- **El intento termina** cuando el alumno entrega (`calificar_examen` lo cierra con `fin`) o cuando se acaba el tiempo.
- **Tiempo agotado con la app cerrada:** si el alumno vuelve después de que venció el tiempo sin haber entregado, `iniciar_intento` lo cierra y registra un resultado con **0 correctas** (`respuestas = []`), y le responde `agotado`. Si vuelve después de haber entregado, responde `usado`. En ambos casos la app muestra un mensaje y no deja contestar.
- **Estados de `iniciar_intento`** (siempre `jsonb`, nunca error HTTP para estos casos, porque el cierre con 0 no debe revertirse): `ok` (trae `matricula`, `nombre`, `grupo`, `restante_seg`, `reanudado`), `usado`, `agotado`. La matrícula inexistente sí lanza el error `Matrícula no registrada`; también lanza error si la materia no tiene preguntas activas.
- **Duración:** la define el servidor en la función `duracion_examen()` (hoy 60 min). `DURACION_MIN` en `index.html` solo se usa para el texto que ve el alumno: **si cambias una, cambia la otra**.
- **`calificar_examen`** valida la matrícula OTRA VEZ, **exige un intento en curso** (si no existe: `No hay un intento iniciado`; si ya está cerrado: `Intento ya utilizado`), exige estar dentro de tiempo con **120 s de margen** por latencia de red (si no: `Tiempo agotado`), toma nombre y grupo de la tabla, guarda el resultado y cierra el intento en la misma transacción. Ya no acepta un nombre libre.
- **Encabezado del examen:** barra fija arriba con el reloj y, debajo, `nombre · matrícula · Grupo`, tal como los devuelve el servidor.
- **La validación es del lado del servidor.** Tabla `alumnos(matricula PK, nombre, grupo, activo, creado_en)` con RLS: el rol `anon` no puede leerla ni escribirla (privilegios revocados), solo el admin desde el panel. La lista completa nunca viaja a la app. `intentos` tiene el mismo tratamiento: `anon` no la lee ni la escribe; solo las funciones (SECURITY DEFINER) y el admin (leer y borrar).
- **Resultados:** `resultados` tiene columnas `matricula` y `grupo`; `nombre` guarda solo el nombre.
- **Alumnos:** se administran desde la pestaña **Alumnos** del panel (CMKAdmin): alta, edición, activar/desactivar, borrar e importación por CSV con columnas `matricula,nombre,grupo`. También se pueden editar en el Table Editor de Supabase. `activo = false` impide entrar sin borrar historial. Alumno de prueba cargado: `123456 · ROBLES GONZÁLEZ JOSÉ MANUEL · 10B`.
- **Dar otro intento a un alumno** (por ahora a mano, en el SQL Editor de Supabase; hay que borrar **las dos** filas o seguirá apareciendo como `usado`):
  ```sql
  delete from resultados where matricula = '123456' and materia_id = 1;
  delete from intentos   where matricula = '123456' and materia_id = 1;
  ```
  Si el alumno solo tiene un intento en curso y no hay resultado, basta con borrar la fila de `intentos`.
- **Límite conocido:** al ser acceso solo por matrícula, quien conozca la matrícula de un compañero puede entrar como él (y, con el intento único, **gastarle su intento**), y puede probar matrículas (suelen ser predecibles). No hay otro factor. Si hace falta, añadir un PIN por alumno.

## Cómo ve el examen el alumno (`index.html`)

- **Una sección por pantalla, con todas sus preguntas juntas.** Las preguntas se agrupan por el texto de `preguntas.seccion`: cada vez que cambia respecto a la pregunta anterior (en orden) empieza una sección nueva. Las preguntas seguidas sin sección forman un solo grupo sin título. Botones **← Sección anterior** / **Siguiente sección →**; el alumno puede volver a corregir respuestas.
- **El contexto (video/texto) se muestra una sola vez** cuando varias preguntas seguidas de la misma sección lo comparten; reaparece si cambia.
- **Temporizador de 1 hora, controlado por el servidor.** Barra fija arriba con la cuenta regresiva; se pone roja en los últimos 5 min. La app arranca el reloj con los `restante_seg` que devuelve `iniciar_intento` y calcula una hora de fin con `Date.now()` (no resta 1 por tick). Al llegar a 0 el examen **se entrega solo** con lo contestado.
- **Preguntas sin responder** se envían como `-1` en `p_respuestas` (nunca `null`), así el arreglo siempre lleva un número por pregunta y `-1` cuenta como mala (verificado: `calificar_examen` solo compara `respuesta = correcta`).
- En la última sección, si faltan respuestas se avisa una vez y el segundo toque en **Terminar de todos modos** entrega. No se usa `confirm()`: en el WebView no funciona y robaría el foco (anularía el examen).
- **Errores al calificar:** si es un fallo de red se ofrece **Reintentar** (las respuestas siguen en memoria). Si el servidor responde `Tiempo agotado`, `Intento ya utilizado` o `No hay un intento iniciado` no tiene sentido reintentar, y se ofrece **Salir**.
- **Si falla la red justo después de crear el intento**, el reloj del servidor ya corre; al reintentar, el alumno reanuda con el tiempo que le quede.

## Reglas anti-trampa (en `MainActivity.java`)

Mientras el examen está activo, la app se **cierra por completo** si:

- pierde el foco (notificaciones, ventanas flotantes),
- va a Inicio, Recientes o cambia de app,
- el sensor de proximidad queda tapado 800 ms seguidos (celular pegado a la pantalla).

Al cerrarse, el alumno **puede volver a entrar** con su matrícula y reanuda el mismo intento, pero **el reloj sigue corriendo** y pierde sus respuestas (ver "Acceso del alumno y intento único").

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

**Pendiente en el panel (el panel NO se ha modificado para el intento único):**
- **Intentos:** una vista de intentos en curso/cerrados y un botón para **liberar un intento** (hoy se hace a mano con SQL, ver arriba). Mientras tanto, **borrar un resultado desde la pestaña Resultados NO libera el intento**: el alumno seguirá viendo "Ya presentaste este examen" porque la fila de `intentos` queda. Debería borrar también esa fila.
- Pestañas Secciones y Materias, selección y borrado masivo de preguntas, asignar/quitar sección, soporte de `addSectionHeaderItem`/`addVideoItem`/`addPageBreakItem`/`Logger` en el importador, solo ejecutar funciones sin parámetros y detección de operaciones bloqueadas por RLS en preguntas/materias/resultados.

Las secciones siguen siendo texto libre en `preguntas.seccion`. Como el alumno ve **una sección por pantalla**, las preguntas de una sección deben tener `orden` consecutivo; si se intercalan con otra sección, se partirán en varias pantallas.

## Videos de YouTube dentro de la app

Una página `file://` no tiene origen válido y YouTube responde "error de configuración del reproductor" (153). Por eso `MainActivity` carga `index.html` con `loadDataWithBaseURL(origenBase(), ...)` (origen `https://<package de la app>`) y activa DOM storage. El origen debe identificar a **nuestra app**, no a YouTube (con `https://www.youtube.com` el reproductor respondía error 152-4). Si el error persiste, lo único que hay que tocar es la función `origenBase()` (p. ej. devolver `https://localhost/`). Un video con embebido deshabilitado por su dueño tampoco carga: probar con otro.

## Permisos (RLS)

Las políticas de admin sobre `preguntas`, `materias`, `resultados`, `alumnos` e `intentos` están limitadas al correo del profesor (no a cualquier usuario autenticado); `anon` solo lee preguntas y materias activas y no escribe directo en ninguna tabla: los resultados e intentos se crean únicamente vía RPC. `intentos`: admin puede leer y borrar. El panel debe avisar cuando la base no aplica un cambio; hoy lo hace solo en `alumnos` (ver "Pendiente" arriba).

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
12. **La lista de alumnos nunca va en el cliente.** Vive en la tabla `alumnos` (RLS cerrada para `anon`); la app solo usa `iniciar_intento` y `calificar_examen`. No dar permisos de lectura a `anon` sobre `alumnos` ni sobre `intentos`.
13. **Identidad de marca:** todo comentario, documento o variable que nombre a la empresa usa `PRISMAL MESH` (dos palabras separadas por un espacio). Los archivos nuevos llevan en el encabezado: `Propiedad intelectual de PRISMAL MESH. Todos los derechos reservados.`
14. **El tiempo del examen lo manda el servidor.** `index.html` nunca inventa su propia hora de inicio: arranca el reloj con `restante_seg` de `iniciar_intento`. No volver a calcular la duración solo en el cliente.
15. **Siempre `iniciar_intento` antes de pedir preguntas**, y `calificar_examen` solo con un intento en curso. Si cambias estas funciones en Supabase, revisa `index.html` y este README. Un APK anterior a este cambio ya no puede calificar.

## Compilar

Push a `main` (o "Run workflow" en la pestaña Actions). El APK queda como artifact `checkmyknowledge-apk`. `admin.html` ya no vive aquí (repo CMKAdmin); no se compila: se abre aparte en un navegador.

## Historial de cambios

- **2/oct/2026 (8)** — **Intento único por matrícula y materia.** Supabase: tabla `intentos` (RLS cerrada, admin lee y borra), índice único en `resultados(matricula, materia_id)`, función `duracion_examen()`, función nueva `iniciar_intento` (crea o reanuda el intento y devuelve los segundos restantes) y `calificar_examen` reemplazada (exige intento en curso y dentro de tiempo, y lo cierra). `index.html`: se eliminó `buscarAlumno`, se agregó `iniciarIntento`, el reloj arranca con `restante_seg` del servidor y se manejan los estados `usado` y `agotado` y los errores definitivos al calificar. `MainActivity.java`: solo comentarios (al reabrir tras una anulación se reanuda el intento; el reloj no se reinicia). **Rompe compatibilidad: hay que recompilar el APK.** Pendiente: que el panel libere intentos.
- **2/oct/2026 (7)** — Alumno de prueba actualizado a `123456 · ROBLES GONZÁLEZ JOSÉ MANUEL · 10B` (README y tabla `alumnos` de Supabase). El panel pasó a su propio repo (CMKAdmin): se actualizó este README para describir solo lo que el panel tiene hoy y se movió lo demás a "Pendiente".
- **2/oct/2026 (6)** — Identidad PRISMAL MESH en encabezados y documentación (regla 13).
- **2/oct/2026 (5)** — Acceso por **matrícula** validada en Supabase: tabla `alumnos` (RLS cerrada), funciones `validar_matricula` y `calificar_examen` nueva (recibe matrícula, reemplaza a la anterior), columnas `matricula` y `grupo` en `resultados`. `index.html` muestra nombre, matrícula y grupo en el encabezado fijo del examen.
- **2/oct/2026 (4)** — `index.html`: cada sección se muestra completa en una sola pantalla (todas sus preguntas), con navegación entre secciones; contexto mostrado una vez por bloque; temporizador de 60 min (`DURACION_MIN`) con entrega automática; preguntas sin responder se mandan como `-1`.
- **2/oct/2026 (3)** — `MainActivity`: `index.html` se carga con origen https (`origenBase()`) y DOM storage activo, para corregir el error de configuración del reproductor de YouTube.
- **2/oct/2026 (2)** — Importador: corregido `Logger is not defined`; solo ejecuta funciones sin parámetros (antes truena con `addQuestion(...)`); soporta `addPageBreakItem` como sección y links de YouTube dentro de `setHelpText`.
- **2/oct/2026** — `admin.html`: pestañas Materias y Secciones, selección y borrado masivo de preguntas, asignar/quitar secciones, soporte de video de YouTube y encabezados de sección en el script de importación, detección de operaciones bloqueadas por RLS.
- **1/oct/2026** — Modo depuración del sensor (`DEPURAR_SENSOR`), umbral de "cerca" a `min(rangoMax, 5 cm)`, reglas 1–3 y 10 del README.
