# CheckMyKnowledge

App Android de exámenes de opción múltiple para alumnos, con reglas anti-trampa.
El profesor administra preguntas y ve resultados desde un panel web (`admin.html`).

## Cómo está armado

| Archivo | Qué es |
|---|---|
| `src/main/java/.../MainActivity.java` | Cascarón nativo: abre un WebView con la app y hace cumplir las reglas anti-trampa. |
| `src/main/assets/index.html` | La app del alumno (HTML + JS puro, sin librerías). Habla con Supabase. |
| `admin.html` | Panel del profesor (login, CRUD de preguntas, importar examen por script, resultados). Se abre en un navegador; no va dentro del APK. |
| `src/main/AndroidManifest.xml` | Permisos y bloqueo de orientación / multiventana. |
| `build.gradle.kts`, `settings.gradle.kts` | Compilación Gradle. |
| `.github/workflows/build.yml` | Compila el APK en GitHub Actions. |

Backend: Supabase (tablas `materias`, `preguntas`, `resultados`; función RPC `calificar_examen`).

## Reglas anti-trampa (en `MainActivity.java`)

Mientras el examen está activo, la app se **cierra por completo** y el alumno empieza de cero si:

- pierde el foco (notificaciones, ventanas flotantes),
- va a Inicio, Recientes o cambia de app,
- el sensor de proximidad queda tapado 800 ms seguidos (celular pegado a la pantalla).

Además: capturas y grabación de pantalla bloqueadas (`FLAG_SECURE`), botón Atrás desactivado,
sin orientación horizontal ni multiventana, sin navegación a otros sitios.

## Reglas para quien modifique el código (persona o IA)

1. **Comenta el código.** Todo cambio debe explicar el *porqué*, no solo el qué. Este repo prioriza comentarios claros para que cualquiera pueda retomarlo.
2. **Entrega archivos completos**, no fragmentos.
3. **Nunca se envía la respuesta correcta al cliente.** `index.html` solo pide `texto, opciones, contexto, seccion`; la calificación la hace la función `calificar_examen` en Supabase. No agregar `correcta` al `select` del cliente.
4. **Cualquier regla anti-trampa nueva debe terminar en `anularExamen()`** de `MainActivity.java`.
5. **Los links externos romperían el examen**: abrir otra app (p. ej. YouTube) hace que la app pierda el foco y se anule. Por eso los videos van *embebidos* (iframe), nunca como link.
6. **El puente JS↔Java** (`Puente`, expuesto como `window.Android`) tiene tres métodos: `iniciarExamen`, `terminarExamen`, `salir`. Si cambias nombres, cámbialos en `index.html` también.
7. **No volver a agregar pasos al workflow que hagan commit/push** al repo (causaron un run fallido; ver nota en `build.yml`).
8. La clave `anon` de Supabase en `index.html` y `admin.html` es pública por diseño; la seguridad real está en las políticas RLS de la base de datos.

## Compilar

Push a `main` (o "Run workflow" en la pestaña Actions). El APK queda como artifact `checkmyknowledge-apk`.
