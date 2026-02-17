# Files to upload to GitHub

Upload the **entire project** except build artefacts. Use the `.gitignore` so Git will exclude them automatically.

## Include (upload)

| Path | Purpose |
|------|---------|
| `app/src/main/` | Kotlin source, resources, AndroidManifest, C++ (vmx_jni) |
| `app/build.gradle.kts` | App build config |
| `build.gradle.kts` | Root build config |
| `settings.gradle.kts` | Project settings |
| `gradle.properties` | Gradle properties |
| `gradle/wrapper/` | `gradle-wrapper.properties` and `gradle-wrapper.jar` |
| `README.md` | Project description |
| `BUILD_VMX.md` | libvmx build instructions |
| `libvmx/` | libvmx source (if you keep it as a subfolder) |

## Exclude (do not upload)

- `app/build/` — build outputs
- `app/.cxx/` — native build cache
- `.gradle/` — Gradle cache
- `.idea/` — IDE config (optional to exclude)
- `local.properties` — local SDK path (contains machine-specific paths)
- `logs.md`, `errors.md` — debug logs

## Quick start

```bash
git init
git add .
# .gitignore will exclude build/, .gradle/, etc.
git status   # verify only source files are staged
git commit -m "Initial commit"
git remote add origin https://github.com/YOUR_USERNAME/OMT-camera-app.git
git push -u origin main
```

## Note on libvmx

`libvmx` is typically a separate repo. Either:

1. **Copy** its source into `libvmx/` and commit it, or  
2. Add it as a **git submodule**:  
   `git submodule add https://github.com/openmediatransport/libvmx.git libvmx`

Do **not** upload `libvmx.so` or other binaries unless you have rights to redistribute them.
