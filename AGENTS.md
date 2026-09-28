# AGENTS.md
# Instrucciones para cualquier agente
# (Gemini, Claude, GLM, Kimi, u otro)

## Fuentes de verdad — leer al iniciar
En este orden:
1. CONSTITUTION.md (si existe) — reglas inamovibles
2. spec.md (si existe)         — qué se construye
3. decisions.md (si existe)    — qué ya fue decidido
4. PROGRESS.md (si existe)     — qué hizo el agente anterior

## Reglas esenciales
- No implementar nada que no esté en spec.md
- No asumir. Si algo no está claro → preguntar
- No modificar lo que no fue pedido
- Al terminar la sesión → actualizar PROGRESS.md

## Modos disponibles
@sdd          → proyecto nuevo con spec
@feature      → agregar algo a proyecto existente
@debug        → corregir errores
@refactor     → mejorar código sin cambiar comportamiento
@reverse-sdd  → documentar proyecto existente sin spec
@architecture → diseñar estructura de proyecto grande
@constitution → definir reglas inamovibles del proyecto
@quick        → prueba rápida sin spec

## Reglas de negocio de este proyecto
→ Ver CONSTITUTION.md y decisions.md
