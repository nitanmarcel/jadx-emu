# jadx-emu

Extensible jadx plugin for dalvik emulation.

## Writing an extension

An extension is a jar in `<jadx config>/plugins-data/jadx-emu/extensions/` containing a class that implements
`jadx.plugins.emu.api.EmuExtension`, listed in `META-INF/services/jadx.plugins.emu.api.EmuExtension`.

```kotlin
dependencies {
    compileOnly("io.github.nitanmarcel:jadx-emu:0.1.0-beta.5")
    compileOnly("io.github.skylot:jadx-core:1.5.6")
}
```

Automatic — runs on every project where the user enables it:

```kotlin
class MyExtension : EmuExtension {
    override fun info() = EmuExtensionInfo("my-ext", "My extension", "Does something", ExtensionMode.AUTO)

    override fun init(ctx: EmuExtensionContext) {
        ctx.addPass(MyPass(ctx))
    }
}
```

On demand — acts only on classes and methods the user selects from the code viewer's context menu:

```kotlin
class MyExtension : EmuExtension {
    override fun info() = EmuExtensionInfo("my-ext", "My extension", "Does something", ExtensionMode.ON_DEMAND)

    override fun init(ctx: EmuExtensionContext) {
        ctx.addTargetToggleAction("My extension here")
        ctx.addPass(MyPass(ctx))   // checks ctx.targets.appliesTo(mth) before acting
    }
}
```

`ctx.emu` gives the pass access to the emulator; see `jadx.plugins.emu.api.EmuContext`.

## Sample plugin

- [jadx-emu-string-deobfuscator](https://github.com/nitanmarcel/jadx-emu-string-deobfuscator) - string decryptor
- [jadx-emu-dexguard-unpacker](https://github.com/nitanmarcel/jadx-emu-dexguard-unpacker) - on-demand dexguard hidden dex unpacker.
