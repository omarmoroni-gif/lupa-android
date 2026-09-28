# spec.md — Lupa para Android

## 1. Propósito
Aplicación móvil para Android que actúa como una lupa digital, permitiendo amplificar texto y objetos pequeños usando la cámara del dispositivo.

## 2. Tecnologías
- Lenguaje: Kotlin
- Interfaz: XML tradicional + Material Design 3
- Cámara: AndroidX CameraX (Preview, ImageCapture, CameraControl)
- Compatibilidad: Android 7.0 (API 24) en adelante

## 3. Funcionalidades actuales
- [x] Vista previa de la cámara trasera en pantalla completa.
- [x] Gestión de permisos de cámara en tiempo de ejecución.
- [x] Zoom analógico/digital mediante barra deslizante (SeekBar) y gesto de pellizco (Pinch-to-zoom).
- [x] Encendido y apagado de linterna (Torch).
- [x] Congelar cuadro actual en pantalla (Freeze frame) y reanudar.
- [x] Zoom táctil (pellizco) y con barra deslizante sobre la imagen congelada, con paneo/arrastre para recorrerla.
- [x] Guardar captura en la galería del dispositivo (tanto en vivo como congelada).
- [x] Enfoque táctil y autoexposición en el punto exacto donde el usuario toca la pantalla.

## 4. Estructura de archivos
- app/src/main/java/com/lupa/app/MainActivity.kt (Lógica principal de la cámara y controles)
- app/src/main/res/layout/activity_main.xml (Diseño visual con PreviewView, SeekBar y botones)
- app/src/main/AndroidManifest.xml (Permisos de cámara y linterna)
