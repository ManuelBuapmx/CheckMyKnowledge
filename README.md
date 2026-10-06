# CheckMyKnowledge

**Propiedad intelectual exclusiva de PRISMAL MESH.** Todos los derechos reservados. Marca: `PRISMAL MESH` (dos palabras, mayúsculas, separadas por un espacio).

App Android de exámenes de opción múltiple para alumnos, con reglas anti-trampa.
El profesor administra materias, secciones y preguntas, y ve resultados desde un panel web (`admin.html`, repo aparte **CMKAdmin**).

> **Este repo debe ser PRIVADO.** El APK que genera el workflow lleva dentro la clave de firma de llamadas, y los README nombran a un alumno de prueba. En un repo público, cualquier persona con cuenta de GitHub podría descargar el APK desde Actions.

## Cómo está armado

| Archivo | Qué es |
|---|---|
| `src/main/java/.../MainActivity.java` | Cascarón nativo: abre un WebView con la app y hace cumplir las reglas anti-trampa. |
| `src/main/assets/index.html` | La app del alumno (HTML + JS puro, sin librerías). Habla con Supabase. |
| `admin.html` | **Ya no está en este repo:** vive en `ManuelBuapmx/CMKAdmin` (login, preguntas, secciones, alumnos, importar examen por script, resultados, intentos). Se abre en un navegador; no va dentro del APK. Ver el README de ese repo. |
| `src/main/AndroidManifest.xml` | Permisos y bloqueo de orientación / multiventana. |
| `build.gradle.kts`, `settings.gradle.kts` | Compilación Gradle (clave de firma de llamadas, modo depuración del sensor, firma estable del APK y tipo **release**). |
| `.github/workflows/build.yml` | Compila el **APK release firmado** en GitHub Actions. |
| `INSTALACION_ALUMNOS.md` | Hoja para los alumnos: cómo instalar la app y qué avisos esperar. |
| `.gitignore` | Evita subir `build/`, APK y llaves de firma (`*.keystore`, `*.jks`). |

Backend: Supabase (tablas `materias`, `preguntas`, `resultados`, `alumnos`, `intentos`, `config`; funciones RPC `iniciar_intento`, `obtener_preguntas` y `calificar_examen`. `validar_matricula` existe pero está **cerrada** y la app no la usa. Funciones internas `_firma_exigida`, `_verificar_firma` y `_max_reanudaciones`).

## Acceso del alumno y intento único (`index.html` + Supabase)

- **Solo con matrícula.** El alumno escribe su matrícula y toca "Comenzar examen"; la app llama a `iniciar_intento` de Supabase. Si la matrícula existe y está activa, entra; si no, ve "Matrícula no registrada" (sin pistas de matrículas parecidas).
- **Un intento por matrícula y por materia.** La tabla `intentos(matricula, materia_id, inicio, fin, preguntas_entregadas, reanudaciones)` tiene una restricción única `(matricula, materia_id)`. La hora de `inicio` la guarda el **servidor**. Un índice único en `resultados(matricula, materia_id)` es el respaldo: la base nunca acepta dos resultados de la misma matrícula y materia.
- **Cerrar o reiniciar la app NO da otro intento ni detiene el reloj, y tiene un límite de reanudaciones.** El alumno puede volver a entrar con su matrícula y `iniciar_intento` **reanuda** el mismo intento, devolviendo los segundos que le quedan (calculados con el reloj del servidor). Ejemplo: si se le cerró la app a los 30 min, al volver le quedan 30 min menos lo que tardó en reabrirla. Sus **respuestas se pierden** (viven solo en el WebView) y empieza a contestar de nuevo.
- **Límite de reanudaciones (2/oct/2026, regla anti-trampa #1).** Salir de la app y volver ya no es gratis. Una *reanudación* es volver a un intento **al que ya se le entregaron las preguntas** (`obtener_preguntas` marca `intentos.preguntas_entregadas = true`); un fallo de red antes de ver las preguntas no cuenta. Se permiten **2 reanudaciones** (la entrada inicial más 2 regresos); **a la tercera, `iniciar_intento` cierra el intento con 0 correctas (`respuestas = []`, `motivo = 'anulado'`) y responde `anulado`**. El límite vive en la tabla `config` (fila `max_reanudaciones`, hoy `2`); si la fila falta o su valor no es un entero, el servidor usa 2 (falla cerrada). El contador queda en `intentos.reanudaciones` y se copia a `resultados.reanudaciones` al calificar, de modo que el profesor ve **cuántas veces salió y volvió cada alumno aunque haya terminado el examen**. `MAX_REANUDACIONES` en `index.html` solo se usa para los textos que ve el alumno: **si cambias una, cambia la otra**. Para cambiar el límite: `update public.config set valor = '3' where clave = 'max_reanudaciones';`.
- **El intento termina** cuando el alumno entrega (`calificar_examen` lo cierra con `fin`), cuando se acaba el tiempo o cuando se anula por exceso de reanudaciones.
- **Tiempo agotado con la app cerrada:** si el alumno vuelve después de que venció el tiempo sin haber entregado, `iniciar_intento` lo cierra y registra un resultado con **0 correctas** (`respuestas = []`, `motivo = 'agotado'`), y le responde `agotado`. Si vuelve después de haber entregado, responde `usado`. En ambos casos la app muestra un mensaje y no deja contestar. Si el servidor devolviera `restante_seg <= 0` (o un valor no numérico), la app lo trata también como `agotado`. Cualquier estado distinto de `ok` que la app no entienda se trata como examen cerrado: nunca abre el examen.
- **Estados de `iniciar_intento`** (siempre `jsonb`, nunca error HTTP para estos casos, porque el cierre con 0 no debe revertirse): `ok` (trae `matricula`, `nombre`, `grupo`, `restante_seg`, `reanudado`, `reanudaciones` y `max_reanudaciones`), `usado`, `agotado`, `anulado`. La matrícula inexistente sí lanza el error `Matrícula no registrada`; también lanza error si la materia no tiene preguntas activas.
- **Duración:** la define el servidor en la función `duracion_examen()` (hoy 60 min). `DURACION_MIN` en `index.html` solo se usa para el texto que ve el alumno: **si cambias una, cambia la otra**.
- **`calificar_examen`** verifica la firma de la app (ver "Firma de la app"), valida la matrícula OTRA VEZ, **exige un intento en curso** (si no existe: `No hay un intento iniciado`; si ya está cerrado: `Intento ya utilizado`), exige estar dentro de tiempo con **120 s de margen** por latencia de red (si no: `Tiempo agotado`), toma nombre y grupo de la tabla, guarda el resultado (con las reanudaciones del intento) y cierra el intento en la misma transacción. Ya no acepta un nombre libre.
- **Encabezado del examen:** barra fija arriba con el reloj y, debajo, `nombre · matrícula · Grupo`, tal como los devuelve el servidor. Si el intento ya se reanudó, la primera pantalla de cada sección muestra "Reanudación N de 2 permitidas".
- **La validación es del lado del servidor.** Tabla `alumnos(matricula PK, nombre, grupo, activo, creado_en)` con RLS: el rol `anon` no puede leerla ni escribirla (privilegios revocados), solo el admin desde el panel. La lista completa nunca viaja a la app. `intentos` tiene el mismo tratamiento: `anon` no la lee ni la escribe; solo las funciones (SECURITY DEFINER) y el admin (leer y borrar).
- **Resultados:** `resultados` tiene columnas `matricula`, `grupo`, `reanudaciones` (cuántas veces volvió el alumno a su intento) y `motivo` (`agotado` o `anulado` cuando el servidor lo cerró con 0; `null` si el alumno entregó); `nombre` guarda solo el nombre. Los resultados anteriores al 2/oct/2026 (12) tienen `reanudaciones = 0` y `motivo = null`.
- **Alumnos:** se administran desde la pestaña **Alumnos** del panel (CMKAdmin): alta, edición, activar/desactivar, borrar e importación por CSV con columnas `matricula,nombre,grupo`. También se pueden editar en el Table Editor de Supabase. `activo = false` impide entrar sin borrar historial. Alumno de prueba cargado: `123456 · ROBLES GONZÁLEZ JOSÉ MANUEL · 10B`.
- **Dar otro intento a un alumno:** desde la pestaña **Intentos** del panel (botón **Liberar**), que borra el resultado y el intento y deja el contador de reanudaciones en 0. Borrar un resultado desde la pestaña Resultados también borra su intento. A mano, en el SQL Editor de Supabase (hay que borrar **las dos** filas o seguirá apareciendo como `usado`):
  ```sql
  delete from resultados where matricula = '123456' and materia_id = 1;
  delete from intentos   where matricula = '123456' and materia_id = 1;
  ```
  Si el alumno solo tiene un intento en curso y no hay resultado, basta con borrar la fila de `intentos`.
- **Ver quién salió y volvió:** columnas **Salidas** y **Motivo** de la pestaña Resultados del panel, o (solo lectura, SQL Editor):
  ```sql
  select matricula, nombre, grupo, correctas, total, reanudaciones, motivo, creado_en
  from resultados where reanudaciones > 0 or motivo is not null order by creado_en desc;
  ```
- **Límite conocido:** al ser acceso solo por matrícula, quien conozca la matrícula de un compañero puede entrar como él (y, con el intento único, **gastarle su intento**), y puede probar matrículas (suelen ser predecibles). No hay otro factor. Si hace falta, añadir un PIN por alumno.
- **Límite conocido (examen repetido en la misma materia):** el intento único es por `(matrícula, materia)`. Si usas "Reemplazar el examen activo" del panel en una materia donde ya hubo intentos, los alumnos que ya presentaron seguirán viendo "Ya presentaste este examen". Usa una materia nueva para cada examen, o libera los intentos de esa materia desde la pestaña Intentos.
- **Límite conocido (calificación):** `calificar_examen` usa el orden `(orden, id)` de las preguntas **activas al momento de calificar**. No edites, reemplaces, borres ni desactives preguntas de una materia mientras haya alumnos contestando: las posiciones dejarían de coincidir y se calificaría mal sin aviso.

## Firma de la app (que el examen solo se presente desde el APK)

**Problema que resuelve:** la clave `anon` es pública y las funciones RPC son ejecutables por `anon`, así que cualquiera con una matrícula podía presentar el examen desde un navegador o un script (con buscador o IA al lado), sin pasar por ninguna regla anti-trampa del APK. Además las preguntas se podían bajar por REST sin matrícula y a cualquier hora.

**Cómo funciona:**
- Cada llamada del alumno lleva `p_ts` (segundos Unix del celular) y `p_firma` = `HMAC-SHA256(clave, accion|matricula|materia|ts)` en hex minúscula. `accion` es `iniciar`, `preguntas` o `calificar`.
- La firma la genera **solo el APK** (`Puente.firmar()` en `MainActivity.java`, expuesto a la página como `Android.firmar`). `index.html` la pide en **cada** llamada, también en reintentos.
- El servidor la comprueba en `_verificar_firma` **antes** de revelar cualquier dato (así tampoco sirve para adivinar matrículas). Acepta un `ts` de ±5 minutos; si el reloj del celular está desajustado responde `Reloj desajustado` y la app pide corregir la hora. Sin firma o con firma errónea responde `Firma requerida` / `Firma inválida`.
- **Las preguntas ya no se leen por REST.** `index.html` las pide a `obtener_preguntas`, que exige firma válida, alumno activo y un intento en curso y dentro de tiempo, y nunca incluye la respuesta correcta. Usa el mismo orden que `calificar_examen` (`orden, id`), así posición y calificación siempre coinciden.
- **La clave** (`firma_secreto`) vive en la tabla `config` de Supabase (RLS cerrada, sin acceso para `anon` ni `authenticated`) y en el APK como `BuildConfig.FIRMA_SECRETO`, inyectada al compilar desde el secreto de GitHub `CMK_FIRMA_SECRETO`. **Nunca va en el repo ni en `index.html`.** Si el APK se compila sin ella, `build.yml` aborta.
- **Interruptor `exigir_firma`** (tabla `config`). **Verificado el 2/oct/2026: está en `true`.** Si fuera `false`, el servidor NO exigiría firma y las preguntas serían legibles por REST. **Ojo:** `_firma_exigida()` devuelve `false` (firma desactivada) si la fila falta o su valor no es exactamente `'true'`; no avisa. Para activarlo / volver atrás:
  ```sql
  update public.config set valor = 'true' where clave = 'exigir_firma';   -- 'false' para volver atrás
  ```
  Con `true`, la política de lectura de `preguntas` para `anon` deja de dar acceso.
- **Rotar la clave** (hacerlo si se sospecha que se filtró, o entre exámenes si quieres): generar otra en Supabase (`update public.config set valor = encode(extensions.gen_random_bytes(32),'hex') where clave='firma_secreto'; select valor from public.config where clave='firma_secreto';`), actualizar el secreto `CMK_FIRMA_SECRETO` en GitHub y **recompilar y repartir el APK**. Los APK con la clave anterior dejarán de funcionar.

**Qué NO garantiza (límites reales):**
- La clave está dentro del APK: quien lo descompile (jadx, apktool) o simplemente le aplique `strings` puede extraerla y firmar por su cuenta. Sube mucho el esfuerzo respecto a abrir un navegador, pero no es infalible. Con la clave, además, se puede **sabotear** a otros alumnos (iniciar su intento y dejar que se agote) y enviar respuestas arbitrarias (la firma no cubre `p_respuestas`). Una garantía total exigiría atestación del dispositivo (Play Integrity) o celulares controlados.
- Un celular con root o un emulador puede llamar a `Android.firmar` o leer la clave.
- **APK release (5/oct/2026):** el APK que se reparte ya no es de depuración (`isDebuggable = false`, firmado con la llave fija). Además se desactivó la inspección remota del WebView (`setWebContentsDebuggingEnabled(false)`) para que no se pueda llamar a `Android.firmar` desde `chrome://inspect`. Sigue sin ser infalible: el APK se puede descompilar y la clave extraerse.
- Un alumno con la app legítima puede usar otro celular o una computadora a un lado para buscar respuestas; eso no lo evita la firma (solo el sensor, parcialmente).
- **Con el límite de reanudaciones, salir a buscar respuestas sigue siendo posible hasta 2 veces**, pero ya no es gratis ni anónimo: la tercera salida cierra el examen con 0 y las dos primeras quedan registradas en `resultados.reanudaciones`. El profesor decide qué hacer con los alumnos que salieron.
- Quien conozca una matrícula puede iniciar el intento de esa persona desde SU PROPIO celular con el APK legítimo (ver "Límite conocido").
- El examen está disponible a cualquier hora y desde cualquier lugar, con las mismas preguntas en el mismo orden. No hay ventana de aplicación ni código de aula.

## Firma estable del APK (obligatoria para el APK que se reparte)

Cada build en GitHub Actions corre en un equipo nuevo y generaría su propia llave de depuración. Android no deja instalar un APK encima de otro con **distinta firma**, y Google registra la app por su paquete **y la llave con que se firma** (ver "Instalar el APK y reducir las advertencias"). Con una llave fija guardada en secretos, todos los APK salen con la misma firma y se instalan como actualización (siempre que suba `versionCode`).

**Desde el 5/oct/2026 la llave es obligatoria: si faltan los secretos, `build.yml` aborta con un error claro** (antes compilaba con una llave de depuración y solo avisaba).

**Una sola vez:**
1. Genera la llave (en una computadora, o en Termux con `pkg install openjdk-17`):
   ```
   keytool -genkeypair -v -keystore cmk.keystore -alias cmk -keyalg RSA -keysize 2048 -validity 10000
   base64 -w0 cmk.keystore
   ```
   Anota la contraseña del almacén y la de la llave. **Guarda `cmk.keystore` en un lugar seguro fuera del repo**: si la pierdes, tendrás que desinstalar la app de todos otra vez (y volver a registrar la llave ante Google).
2. En GitHub (repo → Settings → Secrets and variables → Actions) crea estos secretos:
   - `CMK_KEYSTORE_B64`: la salida de `base64 -w0 cmk.keystore`.
   - `CMK_KEYSTORE_PASS`: contraseña del almacén.
   - `CMK_KEY_ALIAS`: `cmk` (o el alias que usaste).
   - `CMK_KEY_PASS`: contraseña de la llave.
3. Compila. **La primera instalación con la llave nueva sí exige desinstalar** la versión anterior si estaba firmada con otra llave. Desde ahí, las actualizaciones se instalan encima.

Nunca subas la llave ni sus contraseñas al repo (el `.gitignore` bloquea `*.keystore` y `*.jks`). Para compilar en tu computadora sin la llave, `gradle assembleRelease` firma con la llave de depuración y avisa: ese APK es solo para pruebas.

## Instalar el APK y reducir las advertencias

La app **no está en Play Store**: se reparte como archivo APK ("instalación directa"). Eso implica avisos que **no se pueden eliminar del todo**; sí se pueden reducir y explicar.

**De dónde vienen los avisos:**

| Aviso | Causa | Qué se hace |
|---|---|---|
| "Descargar de todos modos" / "este tipo de archivo puede dañar tu dispositivo" | El navegador desconfía de los `.apk`. | Inevitable; está en la hoja de los alumnos. |
| "Permitir desde esta fuente" | Android pide permiso al navegador o gestor de archivos para instalar apps ajenas a la tienda. | Inevitable, una vez por celular. |
| Play Protect ("app no verificada", "analizar app", o bloqueo) | App nueva, de un desarrollador que Google no conoce, y (antes) compilada como **debug**. | El APK ahora es **release** y se firma con una llave fija. Aun así puede avisar: "Más detalles → Instalar de todos modos" o "Analizar app". |

**Qué se hizo (5/oct/2026, 13):**
- El workflow compila **release** (`assembleRelease`): sin la bandera `debuggable`, que era lo que más alarmaba.
- Firma obligatoria con la **llave fija**: el paquete y la llave son siempre los mismos, así las actualizaciones se instalan encima y se puede registrar ante Google.
- Cada run muestra la **huella SHA-256** del certificado (en el resumen del run, paso "Mostrar huella de la firma").
- `INSTALACION_ALUMNOS.md` explica los avisos a los alumnos.

**Verificación de desarrolladores de Google (Android Developer Verification).** Google exige que las apps instaladas en dispositivos Android certificados pertenezcan a un desarrollador verificado, **también las instaladas fuera de Play**. Según lo publicado (confirmar siempre en `developer.android.com/developer-verification`):
- Desde el **30/sep/2026** se aplica en Brasil, Indonesia, Singapur y Tailandia; la ampliación global está prevista desde **2027**. **México no está en la primera tanda**, pero llegará: conviene registrar la app antes.
- Las apps sin registrar se pueden seguir instalando por `adb` o por un "flujo avanzado" para usuarios expertos, pero con más pasos.
- Tipos de cuenta en la **Android Developer Console**:
  - **Distribución limitada:** gratuita, sin identificación oficial, pensada para clases; **máximo 20 dispositivos autorizados**. No alcanza para un grupo grande.
  - **Distribución completa:** pago único de 25 USD y verificación de identidad; sin tope de dispositivos.
- **Qué registrar:** el paquete `com.prismalmesh.checkmyknowledge` y la huella SHA-256 de la llave (la que muestra el run). Si cambias el paquete o la llave, hay que registrar de nuevo.

**Cómo repartir el APK:**
- Descarga el artifact `checkmyknowledge-apk` del run (se conserva 7 días; hay que iniciar sesión en GitHub) y compártelo por el medio de tu plantel (plataforma del curso, Drive con enlace restringido, etc.). **No lo subas a un repo ni a un sitio público**: lleva la clave de firma de llamadas.
- Entrega a los alumnos `INSTALACION_ALUMNOS.md` (o su texto).
- Prueba **primero** en un celular tuyo, siguiendo la hoja tal cual.

**Camino futuro (no implementado):** publicar en Google Play (pruebas internas) elimina casi todos los avisos, pero exige subir `targetSdk`/`compileSdk` a 36 (requisito de Play desde el 31/ago/2026), migrar el botón Atrás a `OnBackInvokedCallback` (con API 36 `onBackPressed()` deja de recibirse) y revisar que el bloqueo de orientación y multiventana siga aplicando en pantallas grandes. Hoy se descarta porque no se planea usar Play Store.

## Cómo ve el examen el alumno (`index.html`)

- **Una sección por pantalla, con todas sus preguntas juntas.** Las preguntas se agrupan por el texto de `preguntas.seccion`: cada vez que cambia respecto a la pregunta anterior (en orden) empieza una sección nueva. Las preguntas seguidas sin sección forman un solo grupo sin título. Botones **← Sección anterior** / **Siguiente sección →**; el alumno puede volver a corregir respuestas.
- **El contexto (video/texto) se muestra una sola vez** cuando varias preguntas seguidas de la misma sección lo comparten; reaparece si cambia. Si el contexto trae texto **y** un link de YouTube, solo se muestra el video.
- **Temporizador de 1 hora, controlado por el servidor.** Barra fija arriba con la cuenta regresiva; se pone roja en los últimos 5 min. La app arranca el reloj con los `restante_seg` que devuelve `iniciar_intento` y calcula una hora de fin con `performance.now()` (reloj monotónico: cambiar la hora del celular no lo afecta; no resta 1 por tick). Al llegar a 0 el examen **se entrega solo** con lo contestado.
- **Preguntas sin responder** se envían como `-1` en `p_respuestas` (nunca `null`), así el arreglo siempre lleva un número por pregunta y `-1` cuenta como mala (verificado: `calificar_examen` solo compara `respuesta = correcta`).
- En la última sección, si faltan respuestas se avisa una vez y el segundo toque en **Terminar de todos modos** entrega. No se usa `confirm()`: en el WebView no funciona y robaría el foco (anularía el examen).
- **Errores al calificar:** si es un fallo de red se ofrece **Reintentar** (las respuestas siguen en memoria). Si el servidor responde `Tiempo agotado`, `Intento ya utilizado` o `No hay un intento iniciado` no tiene sentido reintentar, y se ofrece **Salir**.
- **Si falla la red justo después de crear el intento**, el reloj del servidor ya corre; al reintentar, el alumno reanuda con el tiempo que le quede (y, si falló antes de recibir las preguntas, sin gastar una reanudación).
- **Mensajes antes de empezar:** la pantalla de matrícula avisa del tiempo, del intento único y de que solo se puede volver a entrar 2 veces. Si `iniciar_intento` responde `anulado`, la app explica que se excedieron las salidas permitidas y que el examen se registró con 0.

## Reglas anti-trampa (en `MainActivity.java`)

Mientras el examen está activo, la app se **cierra por completo** si:

- pierde el foco (notificaciones, ventanas flotantes),
- va a Inicio, Recientes o cambia de app,
- el sensor de proximidad queda tapado 800 ms seguidos (celular pegado a la pantalla).

Al cerrarse, el alumno **puede volver a entrar** con su matrícula y reanuda el mismo intento, pero **el reloj sigue corriendo**, pierde sus respuestas y **cada regreso cuenta como una reanudación: la tercera cierra el examen con 0** (ver "Acceso del alumno y intento único"). La anulación en sí **no se registra en el servidor en el momento** (la app ya se cerró y no puede avisar); el servidor se entera cuando el alumno vuelve, y esa reanudación queda guardada en `intentos.reanudaciones` y luego en `resultados.reanudaciones`. Un alumno que se va y no vuelve no deja marca de reanudación: su intento queda abierto hasta que se cierre por tiempo.

Además: capturas y grabación de pantalla bloqueadas (`FLAG_SECURE`), botón Atrás desactivado,
sin orientación horizontal ni multiventana, sin navegación a otros sitios.

### Sensor de proximidad: detalles y límites

- Se considera "cerca" una lectura menor a `min(rangoMáximoDelSensor, 5 cm)` (`DISTANCIA_CERCA_CM`).
- Solo detecta algo pegado al **borde superior** de la pantalla (donde está el sensor). Un segundo celular al lado o más abajo **no** se detecta: es una defensa parcial.
- Si el equipo no tiene sensor, la defensa no se activa en ese equipo.
- **Estado (2/oct/2026): en pruebas reales sigue sin anular el examen; investigación en pausa.** Para diagnosticar existe el modo depuración (ver abajo).

### Modo depuración del sensor (`DEPURAR_SENSOR`)

**Ya no se edita en `MainActivity.java`.** El valor viene de `BuildConfig.DEPURAR_SENSOR`, que es **`false` por defecto**: el APK que sale de cada push a `main` nunca lleva los avisos. Para un APK de diagnóstico:

- En GitHub: pestaña **Actions → Compilar APK → Run workflow → marcar `depurar_sensor`**. El run deja un aviso amarillo para que no se reparta por error.
- En tu computadora: `gradle assembleRelease -Pcmk.firma=<valor> -Pcmk.depurar=true`.

Con depuración activa la app muestra avisos (Toast) con el sensor detectado, su rango y cada lectura (`valor`, `umbral`, `cerca`, `examenActivo`). Los Toast no roban el foco, así que no anulan el examen.

| Qué ves al tapar el sensor con el examen empezado | Causa |
|---|---|
| Ninguna lectura | El sensor no entrega eventos o no se tapa el sensor correcto |
| `cerca=true examenActivo=false` | Falla el puente JS (`iniciarExamen` no llega) |
| `cerca=true examenActivo=true` y no se cierra | Bug en el temporizador |

**⚠ El APK con depuración activa NO se reparte a los alumnos.**

## Panel del profesor (repo CMKAdmin)

El panel vive en su propio repo, `ManuelBuapmx/CMKAdmin`, y su README es la referencia de qué hace. Pestañas actuales: **Preguntas · Secciones · Alumnos · Importar · Resultados · Intentos**.

- **Preguntas:** alta, edición y borrado (individual, varias o todas las mostradas) con materia, sección y contexto; filtro por materia; asignar o quitar sección en bloque.
- **Secciones:** crear (por rango de orden), renombrar, quitar y borrar con sus preguntas.
- **Alumnos:** lista con búsqueda y filtro por grupo, alta/edición, activar/desactivar, borrar e importación CSV.
- **Importar:** script estilo Apps Script de Forms (`FormApp.create`, `addMultipleChoiceItem`, `createChoice`...) con limpieza y corrección automática; el título de cada `FormApp.create` es la sección de sus preguntas y el `setHelpText` de los encabezados de sección es el contexto (video o texto).
- **Resultados:** alumno, matrícula, grupo, materia, puntaje, salidas (`reanudaciones`), motivo y fecha.
- **Intentos:** intentos en curso y cerrados con su contador de salidas y botón **Liberar**.

Siguen **fuera del panel**: las materias y la tabla `config` (límite de reanudaciones, interruptor de firma): solo por SQL en Supabase.

Las secciones siguen siendo texto libre en `preguntas.seccion`. Como el alumno ve **una sección por pantalla**, las preguntas de una sección deben tener `orden` consecutivo; si se intercalan con otra sección, se partirán en varias pantallas.

## Videos de YouTube dentro de la app

Una página `file://` no tiene origen válido y YouTube responde "error de configuración del reproductor" (153). Por eso `MainActivity` carga `index.html` con `loadDataWithBaseURL(origenBase(), ...)` (origen `https://<package de la app>`) y activa DOM storage. El origen debe identificar a **nuestra app**, no a YouTube (con `https://www.youtube.com` el reproductor respondía error 152-4). Si el error persiste, lo único que hay que tocar es la función `origenBase()` (p. ej. devolver `https://localhost/`). Un video con embebido deshabilitado por su dueño tampoco carga: probar con otro.

El reproductor se pide con `fs: 0`, `modestbranding: 1` e `iv_load_policy: 3` para reducir los enlaces que podrían sacar al alumno de la app (y anular el examen). No elimina todos los posibles (p. ej. el título o "Ver en YouTube" ante un error).

## Permisos (RLS y grants en Supabase)

Las políticas de admin sobre `preguntas`, `materias`, `resultados`, `alumnos` e `intentos` están limitadas al correo del profesor (no a cualquier usuario autenticado); `anon` solo lee materias activas y no escribe directo en ninguna tabla: los resultados e intentos se crean únicamente vía RPC. `intentos`: admin puede leer y borrar. `config`: RLS activada y sin políticas ni privilegios para `anon`/`authenticated` (guarda la clave de firma, el interruptor `exigir_firma` y el límite `max_reanudaciones`). `preguntas`: la política de lectura de `anon` ("anon lee preguntas activas si no se exige firma") solo da acceso mientras `config.exigir_firma` sea `false`, y además `anon` ya no tiene `SELECT` sobre la tabla; las preguntas salen únicamente por `obtener_preguntas`. El admin sigue leyendo todo con sus propias políticas. El panel avisa cuando la base no aplica un cambio en alumnos, borrado de preguntas y resultados, y operaciones de secciones y liberación de intentos.

**Funciones (verificado el 2/oct/2026):** `iniciar_intento`, `obtener_preguntas` y `calificar_examen` existen **solo** en su versión firmada (con `p_ts`, `p_firma`) y son ejecutables por `anon`; sin firma responden `Firma requerida`. `_verificar_firma` solo la ejecuta `service_role`. `_max_reanudaciones()` tiene el `EXECUTE` revocado para `public`, `anon` y `authenticated` (solo la llaman las funciones SECURITY DEFINER). `validar_matricula(text)` tiene el `EXECUTE` **revocado** para `public`, `anon` y `authenticated` (devolvía nombre y grupo de cualquier matrícula sin firma); no se borró. Los privilegios de tabla `TRUNCATE`, `REFERENCES` y `TRIGGER` se quitaron a `anon` y `authenticated`, y a `anon` se le dejó solo `SELECT` sobre `materias`. Los privilegios por defecto del esquema `public` ya no dan `EXECUTE` a `public`, `anon` ni `authenticated` en funciones nuevas: **cada función nueva que deba llamar la app necesita un `grant execute` explícito y debe verificar la firma.**

Para auditar en cualquier momento (solo lectura):
```sql
select p.oid::regprocedure::text as funcion,
       has_function_privilege('anon', p.oid, 'execute') as anon,
       has_function_privilege('authenticated', p.oid, 'execute') as authenticated
from pg_proc p where p.pronamespace = 'public'::regnamespace order by 1;
```

## Reglas para quien modifique el código (persona o IA)

1. **Comenta el código.** Los comentarios son obligatorios: explican el *porqué*, no solo el qué, para que cualquiera pueda retomarlo.
2. **Entrega archivos completos y listos para descargar**, no fragmentos.
3. **Actualiza este README** en cada cambio relevante, y entrégalo **completo** junto con el resto de archivos del cambio (también cuando el cambio sea solo en la base de datos).
4. **Nunca se envía la respuesta correcta al cliente.** `obtener_preguntas` solo devuelve `texto, opciones, contexto, seccion`; la calificación la hace `calificar_examen` en Supabase. No agregar `correcta` a lo que devuelve `obtener_preguntas`.
5. **Cualquier regla anti-trampa nueva debe terminar en `anularExamen()`** de `MainActivity.java`.
6. **Los links externos romperían el examen**: abrir otra app (p. ej. YouTube) hace que la app pierda el foco y se anule. Los videos van *embebidos* (iframe), nunca como link.
7. **El puente JS↔Java** (`Puente`, `window.Android`) tiene cuatro métodos: `iniciarExamen`, `terminarExamen`, `salir` y `firmar`. Si cambias nombres, cámbialos en `index.html` también.
8. **No volver a agregar pasos al workflow que hagan commit/push** al repo (ver nota en `build.yml`).
9. La clave `anon` de Supabase en `index.html` y `admin.html` es pública por diseño; la seguridad real está en las políticas RLS.
10. **`DEPURAR_SENSOR` debe ser `false` en cualquier APK que se reparta a alumnos.** Ya no se edita en el código: solo se enciende con el input `depurar_sensor` del workflow o `-Pcmk.depurar=true`. No volver a ponerlo como constante a mano.
11. **No usar `alert()`/`confirm()`/`prompt()` en `index.html`**: abren un diálogo nativo que roba el foco y anula el examen. Usar mensajes dentro de la página.
12. **La lista de alumnos nunca va en el cliente.** Vive en la tabla `alumnos` (RLS cerrada para `anon`); la app solo usa `iniciar_intento`, `obtener_preguntas` y `calificar_examen`. No dar permisos de lectura a `anon` sobre `alumnos` ni sobre `intentos`, ni reabrir `validar_matricula`.
13. **Identidad de marca:** todo comentario, documento o variable que nombre a la empresa usa `PRISMAL MESH` (dos palabras separadas por un espacio). Los archivos nuevos llevan en el encabezado: `Propiedad intelectual de PRISMAL MESH. Todos los derechos reservados.`
14. **El tiempo del examen lo manda el servidor.** `index.html` nunca inventa su propia hora de inicio: arranca el reloj con `restante_seg` de `iniciar_intento`. No volver a calcular la duración solo en el cliente. **Lo mismo con las reanudaciones: las cuenta y limita el servidor; `MAX_REANUDACIONES` en `index.html` es solo texto.**
15. **Siempre `iniciar_intento` antes de pedir preguntas**, y `calificar_examen` solo con un intento en curso. Si cambias estas funciones en Supabase, revisa `index.html` y este README. Un APK anterior a este cambio ya no puede calificar.
16. **Toda llamada nueva del alumno al servidor debe ir firmada** (`firmar()` en `index.html` → `Android.firmar` → `_verificar_firma` en Supabase). El texto firmado es `accion|matricula|materia|ts` y debe ser IDÉNTICO en el APK y en el servidor. No leer `preguntas` por REST desde el cliente ni dar `SELECT` de nuevo a `anon`.
17. **La clave de firma nunca va en el repo, en `index.html`, en capturas ni en mensajes.** Solo en el secreto `CMK_FIRMA_SECRETO` de GitHub y en la tabla `config` (cerrada). Si se filtra, rotarla (ver "Firma de la app").
18. **Las llaves de firma del APK (`*.keystore`, `*.jks`) y sus contraseñas nunca van en el repo.** Solo como secretos de GitHub (ver "Firma estable del APK"). Guarda una copia de la llave fuera de GitHub.
19. **Funciones nuevas en Supabase:** ya no nacen ejecutables por `anon`. Concede `execute` solo a las que la app deba llamar y haz que verifiquen la firma antes de revelar nada.
20. **Salir de la app no puede volver a ser gratis.** Cualquier cambio en `iniciar_intento`, `obtener_preguntas` o `calificar_examen` debe conservar el conteo de reanudaciones (`intentos.preguntas_entregadas`, `intentos.reanudaciones`), el cierre con `anulado` y el copiado a `resultados.reanudaciones`. Un estado nuevo de `iniciar_intento` debe cerrar el paso en `index.html`, nunca abrir el examen.
21. **El APK que se reparte es siempre el RELEASE firmado con la llave fija** (artifact del workflow). Nunca repartir un APK debug ni uno compilado en local sin llave. No cambiar `applicationId` ni la llave sin avisar: obliga a desinstalar y a volver a registrar la app ante Google.
22. **Si cambias lo que el alumno ve al instalar o usar la app** (avisos, pasos, límites), actualiza también `INSTALACION_ALUMNOS.md`.

## Compilar

**Requisito previo (una sola vez):** crear en GitHub los secretos (repo → Settings → Secrets and variables → Actions → New repository secret):
- `CMK_FIRMA_SECRETO`: el valor de `firma_secreto` de la tabla `config` de Supabase.
- `CMK_KEYSTORE_B64`, `CMK_KEYSTORE_PASS`, `CMK_KEY_ALIAS`, `CMK_KEY_PASS`: la llave de firma estable (ver "Firma estable del APK"). **Desde el 5/oct/2026 también son obligatorios.**

Sin ellos, el workflow aborta con un mensaje que dice cuáles faltan.

Push a `main` (o "Run workflow" en la pestaña Actions). El workflow compila **`gradle assembleRelease`**; el APK queda como artifact `checkmyknowledge-apk` (se conserva **7 días**) y el resumen del run muestra la huella SHA-256 del certificado. Para compilar en tu computadora: `gradle assembleRelease -Pcmk.firma=<valor>` (sin la llave de firma, el release sale firmado con la de depuración: solo pruebas). `admin.html` ya no vive aquí (repo CMKAdmin); no se compila: se abre aparte en un navegador.

**Si el primer run con release falla:** copia el mensaje de error del paso que falló. Las causas más probables son un secreto de la llave que falte o esté mal pegado, o una revisión de lint de `lintVitalRelease` (solo corre en release).

Pendiente de compilación: `gradlew` en el repo, fijar las acciones de GitHub por SHA, y subir `targetSdk` a 35/36 (solo si algún día se publica en Play; ver "Camino futuro"; ojo: con `targetSdk` 35/36 `onBackPressed()` y el bloqueo de orientación/multiventana pueden dejar de aplicar, ver revisión de anomalías). **Pendiente adicional:** registrar la app ante Google (ver "Instalar el APK y reducir las advertencias").

**Pendiente antes del examen real:** borrar el intento y el resultado de prueba de la matrícula `123456` (pestaña Intentos del panel, botón Liberar), **recompilar y repartir el APK release (versión 0.4)**, y probar la instalación en un celular propio con `INSTALACION_ALUMNOS.md`. Un APK anterior sigue funcionando, pero a un alumno anulado le mostraría el mensaje de "tiempo terminado" en vez del de exceso de salidas. **Si la versión instalada estaba firmada con otra llave (p. ej. una compilación debug anterior), el primer cambio exige desinstalarla una vez.**

## Historial de cambios

- **5/oct/2026 (13)** — **APK release e instalación sin tantas advertencias.** `build.gradle.kts`: tipo `release` (sin `debuggable`, sin minificar, firmado con la llave fija; sin llave se firma con la de depuración y avisa), `versionCode` 4 / `versionName` 0.4. `build.yml`: compila `assembleRelease`, la llave de firma pasa a ser **obligatoria** (aborta con mensaje si faltan los cuatro secretos), nuevo paso que muestra la huella SHA-256 del certificado, el artifact sale de `build/outputs/apk/release/`. Nuevo `INSTALACION_ALUMNOS.md`. README: secciones "Instalar el APK y reducir las advertencias" (avisos, verificación de desarrolladores de Google, qué registrar, cómo repartir, camino futuro a Play), "Firma estable" ahora obligatoria, reglas 21 y 22, y actualización del panel (6 pestañas, intentos liberables desde el panel). `MainActivity.java` e `index.html` no cambian. **No se pudo probar la compilación fuera de GitHub: el primer run es la prueba.**
- **2/oct/2026 (12)** — **Límite de reanudaciones** (anomalía crítica 1 de la revisión: salir de la app, buscar respuestas y volver no costaba nada ni dejaba rastro). Supabase (migración `limite_de_reanudaciones`): columnas `intentos.preguntas_entregadas` y `intentos.reanudaciones`; columnas `resultados.reanudaciones` y `resultados.motivo`; fila `config.max_reanudaciones = '2'` y función interna `_max_reanudaciones()` (falla cerrada en 2; `EXECUTE` revocado); `obtener_preguntas` marca `preguntas_entregadas`; `iniciar_intento` cuenta cada regreso a un intento con preguntas ya entregadas y, al exceder el límite, cierra el intento con 0 y responde el estado nuevo `anulado` (también registra `motivo` `agotado`/`anulado`); `calificar_examen` copia las reanudaciones al resultado. Probado de punta a punta con una matrícula falsa dentro de una transacción deshecha (el reintento sin ver preguntas no cuenta; regresos 1 y 2 pasan; el 3.º anula con 0/40, `reanudaciones = 2`, `motivo = anulado`; después el intento queda `usado`). `index.html`: constante `MAX_REANUDACIONES` (solo textos), aviso del límite en la pantalla de matrícula, aviso "Reanudación N de 2" en el examen, mensaje para `anulado`, y cualquier estado desconocido cierra el paso. `MainActivity.java`, `build.gradle.kts` y el workflow no cambian. **Hay que recompilar y repartir el APK.**
- **2/oct/2026 (11)** — **Código y GitHub tras la revisión de anomalías.** `build.gradle.kts`: `BuildConfig.DEPURAR_SENSOR` (false por defecto, se enciende con `-Pcmk.depurar=true` o `CMK_DEPURAR_SENSOR`), firma estable opcional con llave de secretos (`CMK_KEYSTORE_*`), `versionCode` 3 / `versionName` 0.3. `build.yml`: permisos mínimos (`contents: read`), input `depurar_sensor`, paso que prepara la llave de firma, `timeout-minutes` y `retention-days: 7` del artifact. `MainActivity.java`: `DEPURAR_SENSOR` sale de `BuildConfig` y se corrigió el comentario sobre iframes y `shouldOverrideUrlLoading`. `index.html`: reloj con `performance.now()`, arreglo del reloj huérfano y de `restante_seg <= 0`, reproductor de YouTube con `fs: 0`, `modestbranding` e `iv_load_policy`. `.gitignore`: APK y llaves. README: repo privado, secciones nuevas y límites conocidos documentados. **Hay que desinstalar el APK anterior una vez si se activa la firma estable.**
- **2/oct/2026 (10)** — **Endurecimiento de permisos en Supabase** (migración `cerrar_funciones_sin_firma_y_endurecer_permisos`): `EXECUTE` de `validar_matricula` revocado a `public`/`anon`/`authenticated`; se quitaron `TRUNCATE`, `REFERENCES` y `TRIGGER` a `anon` y `authenticated`, y los privilegios sobrantes de `anon` en `materias`, `preguntas` y `resultados`; los privilegios por defecto de funciones nuevas ya no incluyen a `anon`. Se verificó que no hay sobrecargas sin firma, que `exigir_firma` está en `true` y que las tres RPC rechazan llamadas sin firma. No requiere recompilar el APK.
- **2/oct/2026 (9)** — **Firma de la app** (anomalía crítica 1: el examen se podía presentar sin el APK usando la API pública). Supabase: tabla `config` (clave `firma_secreto` e interruptor `exigir_firma`, creado en `false`), funciones `_firma_exigida` y `_verificar_firma`, función nueva `obtener_preguntas`, y `iniciar_intento` / `calificar_examen` reemplazadas con parámetros `p_ts` y `p_firma` (además `calificar_examen` desempata por `id`); la política de lectura de `preguntas` para `anon` ahora depende del interruptor. `index.html`: función `firmar()`, las tres llamadas van firmadas, las preguntas se piden por RPC y hay mensajes para `Reloj desajustado` y `Firma`. `MainActivity.java`: método `Puente.firmar()` y depuración remota del WebView desactivada. `build.gradle.kts`: `BuildConfig.FIRMA_SECRETO` desde `CMK_FIRMA_SECRETO`, `versionCode` 2 / `versionName` 0.2. `build.yml`: pasa el secreto y aborta si falta.
- **2/oct/2026 (8)** — **Intento único por matrícula y materia.** Supabase: tabla `intentos` (RLS cerrada, admin lee y borra), índice único en `resultados(matricula, materia_id)`, función `duracion_examen()`, función nueva `iniciar_intento` (crea o reanuda el intento y devuelve los segundos restantes) y `calificar_examen` reemplazada (exige intento en curso y dentro de tiempo, y lo cierra). `index.html`: se eliminó `buscarAlumno`, se agregó `iniciarIntento`, el reloj arranca con `restante_seg` del servidor y se manejan los estados `usado` y `agotado` y los errores definitivos al calificar. `MainActivity.java`: solo comentarios (al reabrir tras una anulación se reanuda el intento; el reloj no se reinicia). **Rompe compatibilidad: hay que recompilar el APK.**
- **2/oct/2026 (7)** — Alumno de prueba actualizado a `123456 · ROBLES GONZÁLEZ JOSÉ MANUEL · 10B` (README y tabla `alumnos` de Supabase). El panel pasó a su propio repo (CMKAdmin): se actualizó este README para describir solo lo que el panel tiene hoy y se movió lo demás a "Pendiente".
- **2/oct/2026 (6)** — Identidad PRISMAL MESH en encabezados y documentación (regla 13).
- **2/oct/2026 (5)** — Acceso por **matrícula** validada en Supabase: tabla `alumnos` (RLS cerrada), funciones `validar_matricula` y `calificar_examen` nueva (recibe matrícula, reemplaza a la anterior), columnas `matricula` y `grupo` en `resultados`. `index.html` muestra nombre, matrícula y grupo en el encabezado fijo del examen.
- **2/oct/2026 (4)** — `index.html`: cada sección se muestra completa en una sola pantalla (todas sus preguntas), con navegación entre secciones; contexto mostrado una vez por bloque; temporizador de 60 min (`DURACION_MIN`) con entrega automática; preguntas sin responder se mandan como `-1`.
- **2/oct/2026 (3)** — `MainActivity`: `index.html` se carga con origen https (`origenBase()`) y DOM storage activo, para corregir el error de configuración del reproductor de YouTube.
- **2/oct/2026 (2)** — Importador: corregido `Logger is not defined`; solo ejecuta funciones sin parámetros (antes truena con `addQuestion(...)`); soporta `addPageBreakItem` como sección y links de YouTube dentro de `setHelpText`.
- **2/oct/2026** — `admin.html`: pestañas Materias y Secciones, selección y borrado masivo de preguntas, asignar/quitar secciones, soporte de video de YouTube y encabezados de sección en el script de importación, detección de operaciones bloqueadas por RLS.
- **1/oct/2026** — Modo depuración del sensor (`DEPURAR_SENSOR`), umbral de "cerca" a `min(rangoMax, 5 cm)`, reglas 1–3 y 10 del README.
