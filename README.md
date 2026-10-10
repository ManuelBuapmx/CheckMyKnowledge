# CheckMyKnowledge

**Propiedad intelectual exclusiva de PRISMAL MESH.** Todos los derechos reservados. Marca: `PRISMAL MESH` (dos palabras, mayúsculas, separadas por un espacio).

App Android de exámenes de opción múltiple para alumnos, con reglas anti-trampa.
El profesor administra materias (exámenes), secciones, preguntas, alumnos, asignaciones y horarios de acceso, y ve resultados desde un panel web (`admin.html`, repo aparte **CMKAdmin**).

## Cómo está armado

| Archivo | Qué es |
|---|---|
| `src/main/java/.../MainActivity.java` | Cascarón nativo: abre un WebView con la app y hace cumplir las reglas anti-trampa. |
| `src/main/assets/index.html` | La app del alumno (HTML + JS puro, sin librerías). Habla con Supabase. |
| `admin.html` | Panel del profesor (login, preguntas, secciones, materias, asignaciones, alumnos, importar examen por script, resultados, intentos). Vive en el repo aparte **CMKAdmin** y se abre en un navegador; no va dentro del APK. Ver el README de ese repo. |
| `src/main/AndroidManifest.xml` | Permisos y bloqueo de orientación / multiventana. |
| `build.gradle.kts`, `settings.gradle.kts` | Compilación Gradle. |
| `.github/workflows/build.yml` | Compila el APK en GitHub Actions. |

Backend: Supabase (tablas `materias`, `preguntas`, `resultados`, `alumnos`, `intentos`, `asignaciones`; funciones RPC `validar_matricula`, `iniciar_intento`, `calificar_examen`, `materias_disponibles`).
- El acceso real se decide en el servidor: `iniciar_intento` valida `materias.activa`, `solo_asignados`, `abre_en`/`cierra_en` y retorna el motivo con `_motivo_no_disponible`.
- `materias`: columnas `abre_en timestamptz`, `cierra_en timestamptz`, `solo_asignados boolean default false`; sin datos se comportan como antes.
- `asignaciones(id, matricula → alumnos, materia_id → materias, abre_en, cierra_en, creado_en)` con `UNIQUE(matricula, materia_id)`; RLS solo para admin.
- `intentos`: control de inicio, reanudaciones, cierre por tiempo y motivo `agotado`.
- `materias_disponibles(p_matricula)` devuelve las materias válidas para ese alumno; la app del alumno debe usarla en lugar de leer `materias` públicamente.
- Migración aplicada: `asignaciones_y_ventanas_de_acceso` y, para cierres automáticos, `cerrar_intentos_vencidos_con_cron`.

## Acceso del alumno (`index.html` + Supabase)

- **Solo con matrícula.** El alumno escribe su matrícula; la app llama a la función `validar_matricula` de Supabase. Si existe y está activa, entra; si no, ve "Matrícula no registrada" (sin pistas de matrículas parecidas).
- **Encabezado del examen:** barra fija arriba con el reloj y, debajo, `nombre · matrícula · Grupo`, tal como los devuelve el servidor.
- **La validación es del lado del servidor.** Tabla `alumnos(matricula PK, nombre, grupo, activo, creado_en)` con RLS: `anon` no puede leerla ni escribirla; solo el admin desde el panel. La lista completa nunca viaja a la app.
- **Funciones RPC:** `validar_matricula`, `iniciar_intento`, `calificar_examen`, `materias_disponibles`.
- **Intentos (`intentos`):** un intento por `(matrícula, materia)`, creado por `iniciar_intento`. El servidor lleva el inicio, las reanudaciones y el fin. Al pasar el máximo de reanudaciones (`_max_reanudaciones`) el intento se anula con 0; si se acaba la duración (`duracion_examen()`) se cierra como `agotado`.
- **Peticiones firmadas:** `iniciar_intento` y `calificar_examen` reciben `p_ts` y `p_firma` y las valida `_verificar_firma`.
- **Resultados:** `resultados` incluye `matricula`, `grupo`, `reanudaciones` y `motivo` (`anulado` / `agotado` cuando el servidor lo cerró en 0).
- **Alumnos:** se administran desde la pestaña **Alumnos** del panel (CMKAdmin): alta, edición, activar/desactivar, borrar e importación por CSV con columnas `matricula,nombre,grupo`. `activo = false` impide entrar sin borrar historial.
- **Límite conocido:** acceso solo por matrícula; quien conozca la matrícula de un compañero puede entrar como él y probar matrículas predecibles. Las asignaciones y los horarios reducen el daño, pero no son un segundo factor. Si hace falta: PIN por alumno.

## Cómo ve el examen el alumno (`index.html`)

- **Una sección por pantalla, con todas sus preguntas juntas.** Las preguntas se agrupan por el texto de `preguntas.seccion`: cada vez que cambia respecto a la pregunta anterior (en orden) empieza una sección nueva. Las preguntas seguidas sin sección forman un solo grupo sin título. Botones **← Sección anterior** / **Siguiente sección →**; el alumno puede volver a corregir respuestas.
- **El contexto (video/texto) se muestra una sola vez** cuando varias preguntas seguidas de la misma sección lo comparten; reaparece si cambia.
- **Temporizador** con barra fija arriba; la duración real la manda el servidor (`restante_seg`), y se pone roja en los últimos 5 min. Al llegar a 0 el examen se entrega solo con lo contestado.
- **Preguntas sin responder** se envían como `-1` en `p_respuestas` (nunca `null`), así el arreglo siempre lleva un número por pregunta y `-1` cuenta como mala.
- En la última sección, si faltan respuestas se avisa una vez y el segundo toque en **Terminar de todos modos** entrega. No se usa `confirm()` en el alumno: en el WebView no funciona y robaría el foco (anularía el examen).
- Cuando el alumno reabre la app, el servidor decide si es una reanudación permitida o si el intento se anula. Si no vuelve, el cron lo cierra como `agotado` con 0 pasada la duración.

- pierde el foco (notificaciones, ventanas flotantes),
- va a Inicio, Recientes o cambia de app,
- el sensor de proximidad queda tapado 800 ms seguidos (celular pegado a la pantalla).

Mientras el examen está activo, la app se **cierra por completo** si:
- pierde el foco (notificaciones, ventanas flotantes),
- va a Inicio, Recientes o cambia de app,
- el sensor de proximidad queda tapado 800 ms seguidos (celular pegado a la pantalla).

Además: capturas y grabación de pantalla bloqueadas (`FLAG_SECURE`), botón Atrás desactivado, sin orientación horizontal ni multiventana, sin navegación a otros sitios. Al reabrir, el servidor decide si es una reanudación permitida o si el intento se anula; si no vuelve, el cron cierra su intento como `agotado` con 0 pasada la duración.

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

## Panel del profesor (`admin.html` / repo CMKAdmin)

Pestañas: **Preguntas · Secciones · Materias · Asignaciones · Alumnos · Importar · Resultados · Intentos**.

### Materias (exámenes)
- Crear (nacen **desactivadas**), renombrar, ordenar, **activar/desactivar** y borrar. Borrar puede rechazarse por la base si hay preguntas, resultados o intentos ligados; para ocultar sin borrar, desactivar.
- **Horario de acceso:** "Abre el" y "Cierra el" (fecha y hora). Atajos: **Cerrar acceso ahora** y **Quitar horario**. Valida que el cierre sea posterior a la apertura.
- **Solo alumnos asignados:** restringe el examen a quienes estén en la pestaña **Asignaciones**.
- Cada tarjeta muestra el estado calculado: Desactivado / Abre … / Abierto hasta … / Cerrado.

### Asignaciones
- Se elige el examen; se busca por matrícula o nombre, se filtra por grupo y se marcan alumnos (o "todos los mostrados"). **Asignar seleccionados** hace upsert por `(matrícula, materia)`.
- **Ventana propia opcional** (abre/cierra) para los asignados; tiene prioridad sobre la de la materia. Si se deja vacía, hereda la de la materia.
- Lista de asignados con fechas y **Quitar seleccionados**.
- Si el examen sigue abierto a todos, avisa y ofrece **Restringir a alumnos asignados**.

### Preguntas
- Filtro por materia. Casilla por pregunta y "seleccionar todas las mostradas". Con las marcadas: **Asignar sección**, **Quitar sección** y **Borrar seleccionadas**. **Borrar todas las mostradas** borra lo que muestre el filtro actual.
- Cada pregunta muestra su sección como etiqueta.

### Secciones
Las secciones **no son una tabla**: son texto libre en `preguntas.seccion`. *Crear* = poner ese texto a las preguntas de una materia por rango de `orden`. *Renombrar* cambia el texto en todas sus preguntas. *Quitar* vacía el texto (las preguntas se conservan). *Borrar con preguntas* las elimina.
- ⚠ Como el alumno ve **una sección por pantalla**, las preguntas de una sección deben tener `orden` consecutivo; si se intercalan con otra sección, se partirán en varias pantallas.

### Alumnos
Lista con búsqueda y filtro por grupo; alta/edición (la matrícula no se edita), activar/desactivar, borrar (borra también sus asignaciones; los resultados se conservan) e importar CSV (coma, punto y coma o tabulador; con o sin encabezado; upsert por matrícula).

### Resultados e Intentos
- **Resultados:** alumno, matrícula, grupo, materia, puntaje, **Salidas** y **Motivo**. Borrar un resultado también borra su intento.
- **Intentos:** en curso y cerrados, con contador de salidas y botón **Liberar** (borra intento y resultado; el alumno puede presentar de nuevo con 0 salidas). **"En curso" solo significa que el intento no tiene `fin`**: un alumno que abandonó el examen aparece así hasta ~63 min después de su inicio, cuando el cron lo pasa a "Tiempo agotado".
- Ambas pestañas se recargan al abrirlas.

### Importar por script (Apps Script de Forms)
Se pega un script casi tal cual de Google Forms; **Limpiar y corregir** arregla bloques ``` y comillas tipográficas, y **Procesar** ejecuta el script en el navegador y muestra vista previa antes de guardar.

| Llamada | Efecto |
|---|---|
| `FormApp.create('Título')` | El título es la **sección** de sus preguntas (varios `create` se importan en orden) |
| `addMultipleChoiceItem()` / `createChoice(valor, esCorrecta)` | Pregunta de opción múltiple |
| `addSectionHeaderItem()` / `addPageBreakItem()` + `setTitle` | Cambia la **sección** de las preguntas siguientes |
| `…setHelpText('texto o link')` | Es el **contexto** de las preguntas siguientes |
| `addVideoItem().setVideoUrl(url)` | Esa URL es el contexto de las preguntas siguientes |
| `addTextItem` / `addListItem` | Se ignoran |
| Otras llamadas de Forms / `Logger` / `Utilities`… | Se ignoran y se avisa cuáles |

### Videos de YouTube dentro de la app
Una página `file://` no tiene origen válido; por eso `MainActivity` carga `index.html` con `loadDataWithBaseURL(origenBase(), ...)` y activa DOM storage. Si el error persiste, cambiar solo `origenBase()`. Un video con embebido deshabilitado por su dueño tampoco carga.

### Permisos (RLS) y errores
- Todas las escrituras del panel que deben afectar filas usan `escribirConfirmado()`; si la base no aplicó el cambio, avisa en lugar de fingir éxito.
- Sesión vencida (401): se muestra el login con el aviso; ninguna pantalla se redibuja encima.
- Las políticas "admin puede …" se basan en el correo del profesor (`auth.jwt() ->> 'email'`). Cada tabla nueva del panel necesita sus políticas.

**Pendiente (descrito en versiones anteriores de este README, pero NO incluido en el panel actual):** pestañas Secciones y Materias, selección y borrado masivo de preguntas, asignar/quitar sección, soporte de `addSectionHeaderItem`/`addVideoItem`/`addPageBreakItem`/`Logger` en el importador, y detección de operaciones bloqueadas por RLS en preguntas/materias/resultados.

## Reglas para quien modifique el código (persona o IA)

1. **Comenta el código.** Los comentarios son obligatorios: explican el *porqué*, no solo el qué, para que cualquiera pueda retomarlo.
2. **Entrega archivos completos y listos para descargar**, no fragmentos.
3. **Actualiza este README** en cada cambio relevante (también cuando el cambio sea solo en la base de datos).
4. **Nunca se envía la respuesta correcta al cliente.** `index.html` solo pide `texto, opciones, contexto, seccion`; la calificación la hace `calificar_examen` en Supabase. No agregar `correcta` al `select` del cliente.
5. **Cualquier regla anti-trampa nueva debe terminar en `anularExamen()`** de `MainActivity.java`.
6. **Los links externos romperían el examen**: abrir otra app (p. ej. YouTube) hace que la app pierda el foco y se anule. Los videos van *embebidos* (iframe), nunca como link.
11. **No usar `alert()`/`confirm()`/`prompt()` en `index.html`**: abren un diálogo nativo que roba el foco y anula el examen. (En `admin.html` sí se pueden usar.)
12. **La lista de alumnos nunca va en el cliente del alumno.** Vive en `alumnos` (RLS cerrada para `anon`); la app solo usa RPC. No dar permisos de lectura a `anon` sobre `alumnos` ni `asignaciones`.
13. **Identidad de marca:** todo comentario, documento o variable que nombre a la empresa usa `PRISMAL MESH` (dos palabras separadas por un espacio). Los archivos nuevos llevan en el encabezado: `Propiedad intelectual de PRISMAL MESH. Todos los derechos reservados.`
14. **El acceso a exámenes (activo, asignado, horario) se decide SIEMPRE en el servidor** (`iniciar_intento` → `_motivo_no_disponible`). El cliente solo lo muestra; nunca confiar en ocultar botones o en la hora del dispositivo.
15. **Si cambias la firma de `iniciar_intento` o `calificar_examen`**, actualiza `index.html` (parámetros `p_ts` y `p_firma`) y este README.
16. **Un intento vencido nunca debe quedar abierto, y el cron nunca debe adelantarse a una entrega legítima.** El job `cerrar-intentos-vencidos` debe seguir activo y esperar **más** que el margen de `calificar_examen` (hoy 120 s; el cron usa 150 s). Si cambias ese margen, la duración (`duracion_examen()`) o las columnas de `intentos`/`resultados`, revisa `_cerrar_intentos_vencidos` y conserva su `EXECUTE` revocado para `anon`/`authenticated`.
4. **Nunca se envía la respuesta correcta al cliente.** `index.html` solo pide `texto, opciones, contexto, seccion`; la calificación la hace `calificar_examen` en Supabase. No agregar `correcta` al `select` del cliente.
5. **Cualquier regla anti-trampa nueva debe terminar en `anularExamen()`** de `MainActivity.java`.
6. **Los links externos romperían el examen**: abrir otra app (p. ej. YouTube) hace que la app pierda el foco y se anule. Los videos van *embebidos* (iframe), nunca como link.
7. **El puente JS↔Java** (`Puente`, `window.Android`) tiene tres métodos: `iniciarExamen`, `terminarExamen`, `salir`. Si cambias nombres, cámbialos en `index.html` también.
8. **No volver a agregar pasos al workflow que hagan commit/push** al repo (ver nota en `build.yml`).
9. La clave `anon` de Supabase en `index.html` y `admin.html` es pública por diseño; la seguridad real está en las políticas RLS.
10. **`DEPURAR_SENSOR` debe estar en `false`** en cualquier APK que se reparta a alumnos.
## Compilar

Push a `main` (o "Run workflow" en la pestaña Actions). El APK queda como artifact `checkmyknowledge-apk`. `admin.html` no se compila: se sube/abre aparte.

## Historial de cambios

- **8/oct/2026** — **Cierre automático de intentos vencidos.** Se añadió `pg_cron` + `_cerrar_intentos_vencidos()` + job `cerrar-intentos-vencidos` cada minuto; cierra intentos abandonados en `agotado` con 0, con margen extra sobre `calificar_examen` y revisando `duracion_examen()`. Sin cambios en `admin.html`, `index.html` ni el APK. Nueva sección y regla 16.
- **7/oct/2026** — Asignación de exámenes a alumnos específicos, activar/desactivar exámenes y horario de acceso con ventana propia por alumno. Migración `asignaciones_y_ventenas_de_acceso` (columnas en `materias`, tabla `asignaciones`, `_motivo_no_disponible`, `materias_disponibles`, verificación en `iniciar_intento`). `admin.html`: pestañas **Materias** y **Asignaciones**. Pendiente: `index.html` debe usar `materias_disponibles`.
- **5/oct/2026** — `admin.html`: pestañas Secciones, Alumnos (con CSV) e Intentos; selección y borrado masivo de preguntas; borrados confirmados por filas afectadas; manejo de sesión vencida; importador con limpieza/corrección automática, videos y texto de apoyo; Resultados con Salidas y Motivo. Servidor: intentos, reanudaciones y peticiones firmadas.
- **2/oct/2026 (6)** — Identidad PRISMAL MESH en encabezados y documentación (regla 13).
- **2/oct/2026 (5)** — Acceso por **matrícula** validada en Supabase: tabla `alumnos` (RLS cerrada), `validar_matricula`, `calificar_examen` por matrícula, columnas `matricula` y `grupo` en `resultados`.
- **2/oct/2026 (4)** — `index.html`: una sección completa por pantalla, contexto mostrado una vez por bloque, temporizador con entrega automática, preguntas sin responder como `-1`.
- **2/oct/2026 (3)** — `MainActivity`: `index.html` con origen https y DOM storage, para corregir el error del reproductor de YouTube.
- **2/oct/2026 (2)** — Importador: corregido `Logger is not defined`; solo ejecuta funciones sin parámetros; soporta `addPageBreakItem` y links de YouTube en `setHelpText`.
- **2/oct/2026** — `admin.html`: pestañas Materias y Secciones, borrado masivo, video y encabezados de sección en el importador, detección de operaciones bloqueadas por RLS.
- **1/oct/2026** — Modo depuración del sensor (`DEPURAR_SENSOR`), umbral de "cerca" a `min(rangoMax, 5 cm)`, reglas 1–3 y 10 del README.
- **1/oct/2026** — Modo depuración del sensor (`DEPURAR_SENSOR`), umbral de "cerca" a `min(rangoMax, 5 cm)`, reglas 1–3 y 10 del README.
