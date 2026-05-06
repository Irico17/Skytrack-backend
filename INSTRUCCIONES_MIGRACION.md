# Instrucciones para Migrar a Repositorio Personal

## Opción 1: Mantener historial completo (Recomendado)

Esta opción preserva todo el historial de commits del proyecto original.

```bash
# 1. Cambiar el remote origin al nuevo repositorio
git remote set-url origin https://github.com/TU-USUARIO/NUEVO-REPO.git

# 2. Verificar que cambió correctamente
git remote -v

# 3. Hacer push de todas las ramas
git push -u origin --all

# 4. Hacer push de todos los tags (si existen)
git push -u origin --tags
```

## Opción 2: Empezar historial desde cero

Esta opción crea un repositorio nuevo sin el historial previo.

```bash
# 1. Eliminar el directorio .git actual
Remove-Item -Recurse -Force .git

# 2. Inicializar nuevo repositorio
git init

# 3. Agregar todos los archivos
git add .

# 4. Hacer commit inicial
git commit -m "Initial commit: Sistema de planificación de rutas de maletas"

# 5. Agregar el nuevo remote
git remote add origin https://github.com/TU-USUARIO/NUEVO-REPO.git

# 6. Crear rama principal
git branch -M main

# 7. Hacer push inicial
git push -u origin main
```

## Opción 3: Mantener ambos remotos (Dual remote)

Esta opción te permite mantener conexión con el repo original y el nuevo.

```bash
# 1. Renombrar el remote actual
git remote rename origin upstream

# 2. Agregar tu nuevo repositorio como origin
git remote add origin https://github.com/TU-USUARIO/NUEVO-REPO.git

# 3. Verificar remotos
git remote -v
# Deberías ver:
# origin    https://github.com/TU-USUARIO/NUEVO-REPO.git (fetch)
# origin    https://github.com/TU-USUARIO/NUEVO-REPO.git (push)
# upstream  https://github.com/rom-va/scheduling-core.git (fetch)
# upstream  https://github.com/rom-va/scheduling-core.git (push)

# 4. Hacer push a tu nuevo repositorio
git push -u origin BackEnd

# 5. Si quieres hacer main la rama principal
git checkout -b main
git push -u origin main
```

## Recomendación

**Usa la Opción 1** si:
- Quieres mantener el historial de commits
- No te importa que se vea el origen del proyecto
- Quieres poder rastrear cambios históricos

**Usa la Opción 2** si:
- Quieres empezar "limpio"
- No necesitas el historial previo
- Prefieres un commit inicial único

**Usa la Opción 3** si:
- Quieres mantener sincronización con el repo original
- Planeas contribuir de vuelta al proyecto original
- Quieres poder hacer pull de actualizaciones del upstream

## Después de la migración

1. Actualiza la documentación con la nueva URL del repositorio
2. Actualiza cualquier CI/CD o webhooks
3. Informa a colaboradores sobre el nuevo repositorio
