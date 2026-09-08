Aplicación para tablets Android que permita abrir dos ficheros epub con la finalidad de que se lea el mismo libro en un idioma que se quiere aprender y otro en otro idioma que es nativo del usuario 
El primer libro se puede abrir directamente seleccionado un fichero epub
El segundo libro se abrirá cuando el sistema detecte que no hay un segundo fichero, indicando la ruta donde se encuentre dicho segundo libro
La aplicación estará preparada para tablets en formato landscape de tal forma que se pueda llegar a tener el primer libro a la izquiera y el segundo a la derecha
El ancho de la parte de la izquiera es la mitad de la pantalla

## Roles
* android-kotlin-development para preparar la base del programa
*   sobre todo porque los EPUBs trabajan con XHTML y mucho javascript


## Layout principal

* Pantalla landscape dividida en dos
* A la izq. se ve el libro en inglés (el primer libro abierto)
* A la der se ve el libro en castellano y si no está aún abierto mostrará un mensaje indicando que pulsando sobre la parte de la derecha se podrá seleccionar el segundo libro
* En las dos partes, además de mostrar los libros en fondo negro y texto en blanco, se mostrará abajo la página en la que se encuentra y el total de páginas
* A fin de facilitar la lectura se mostrará una barra horizontal de color más claro que el fondo negro indicando el inicio de la página como si fuera una regla horizontal que indica el párrafo más cercado a la parte superior


## Navegación
* se hará mediante scroll vertical y paso de capítulo a capítulo
* Debe poder navegar por el TOC ya sea usando un posible índice dentro del libro o accediendo a él desde un botón (para cada libro) "TOC" que esté en la barra inferior (donde también se ve el porcentaje de avance del libro)

## Renderización
* Se desea mostrar el libro con los estilos del libro a excepción del fondo que se verá negro y el texto que se verá blanco
* Se desea mostrar el porcentaje de avance del libro en la pantalla de cada libro

## Persistencia
En caso de que la aplicación ya tenga los libros abiertos deberá recordar:
* Qué libros están siendo leídos
* En qué página y párrafo se encuentra el libro de la izquierda (si no se consigue trabajar por página/párrafo, deberá tenerse en cuenta el scroll desde el principio del capítulo)
* En qué página y párrafo se encuentra el libro de la derecha (si no se consigue trabajar por página/párrafo, deberá tenerse en cuenta el scroll desde el principio del capítulo)

* Si se quiere empezar a leer otro libro deberá permitir realizarlo con una opción de menú indicado que se quiere empezar a leer otro libro PERO si se vuelve a seleccionar otra vez un libro en inglés que ya se abrió y que también tenía un libro en castellano y estaban sincronizados, deberá permitir volver a abrirlos en la página párrafo (o desplazamiento de scroll) que tenían dichos libros

## Caso de uso
1. Abro un libro en inglés
2. Abro un libro en castellano
3. Me muevo al 2º capítulo en ambos casos
4. Hago scroll para moverme a la mitad del capítulo en ambos casos
5. Cierro la aplicación
6. La vuelvo a abrir
7. La aplicación deberá abrirse con los dos libros cargados y en la posición exacta donde lo dejó el usuario con el scroll exacto del capĺtulo indicado

## Tecnología
* Gradle >= 9.7
* Kotlin
* Jetpack Compose
* Epublib+slf4j-android
* Que funcione para versión 11 del API de Android
* Gradle con toml
* El mejor estándar en inyección de dependencias
* El mejor estándar para persistencia de datos

## Especificaciones importantes
* Solo para Tablets
* Renderizado como un EPUB normal (XHTML)
* Solo para orientation landscape
* Con densidades no menores a xhdpi
* Resolución no inferior a 1920×1200
* Paquete: org.ecos.logic.twinbooks
* Código, comentarios, documentación, todo en Inglés

## Requisitos técnicos
* Cada vez que se hagan cambios revisa si el proyecto compila