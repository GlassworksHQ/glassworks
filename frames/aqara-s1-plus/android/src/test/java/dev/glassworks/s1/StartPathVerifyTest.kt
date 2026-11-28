package dev.glassworks.s1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/**
 * The APK's own classes verify when the S1 installs them (Android 10, API 29), not at run time on every start
 * (docs/research/art-start-path.md). Android 10's verifier soft-fails a class, and ART verifies it again each time the
 * app starts, when one of its methods:
 *
 * - calls a method or names a class that Android 10 doesn't have, even behind a [dev.glassworks.platform.Platform]
 *   query. Such a call goes in a nested `object ApiNN` (NN its API level) that only the query's branch reaches, as
 *   `PlatformApi.Api30` does; this test lets an `ApiNN` class use APIs up to NN. A call counts whatever class names it:
 *   `stateDescription = …` inside a View subclass names the subclass, and the test follows it up to the platform's
 *   View. So does a newer type anywhere in a class: an argument or result, a field, a supertype, a lambda's interface.
 *   A holder's arguments and results are types Android 10 has.
 * - is one of Kotlin's interface-default bridges (`access$<name>$jd`), which invoke-super the interface itself. The
 *   app's modules are built without them (`jvmDefault = NO_COMPATIBILITY`, build-logic/glassworks.android). A library
 *   published to Maven builds in `DISABLE` mode (the root build.gradle.kts), which has none either and keeps the
 *   `DefaultImpls` its users compiled against: never move a published library to another mode or to
 *   `@JvmDefaultWithoutCompatibility`.
 *
 * Each soft-failed class drags every class that extends or implements it along: before this, 136 of the app's
 * classes, MainActivity, VitroPage, AppManage and Platform among them, were verified again at every start.
 *
 * It reads the compiled classes of every module the S1 app ships (dev.glassworks.*, sh.edm.*) and the SDK's API
 * database (`api-versions.xml` beside the compile SDK's android.jar, passed in by the build).
 */
class StartPathVerifyTest {
    /** The S1's Android: 10. */
    private val panelApi = 29

    private val api by lazy { ApiDatabase.load(File(System.getProperty("glassworks.apiVersions") ?: error("run through Gradle: no glassworks.apiVersions"))) }

    /** The app's own main classes on the test classpath: every module the APK ships, none of the tests. */
    private val appClasses: Map<String, ByteArray> by lazy {
        val out = sortedMapOf<String, ByteArray>()
        for (entry in System.getProperty("java.class.path").split(File.pathSeparator)) {
            if ("UnitTest" in entry) continue // this module's test classes, testing/'s among them
            val f = File(entry)
            fun take(name: String, bytes: () -> ByteArray) {
                if (name.endsWith(".class") && (name.startsWith("dev/glassworks/") || name.startsWith("sh/edm/"))) {
                    out.putIfAbsent(name.removeSuffix(".class"), bytes())
                }
            }
            when {
                f.isDirectory -> f.walkTopDown().filter { it.isFile }.forEach { c -> take(c.relativeTo(f).invariantSeparatorsPath) { c.readBytes() } }
                f.isFile && f.name.endsWith(".jar") -> ZipFile(f).use { z ->
                    z.entries().asSequence().forEach { e -> take(e.name) { z.getInputStream(e).readBytes() } }
                }
            }
        }
        out
    }

    @Test
    fun `no class calls an API above Android 10 outside an ApiNN holder`() {
        val found = appClasses.flatMap { (name, bytes) ->
            val allowed = Regex("""\${'$'}Api(\d+)(\$|$)""").find(name)?.groupValues?.get(1)?.toInt() ?: panelApi
            references(bytes).mapNotNull { ref -> level(ref)?.takeIf { it > allowed }?.let { "${name.replace('/', '.')}: ${ref.shown} (API $it)" } }
        }.distinct()
        assertTrue("scanned only ${appClasses.size} classes", appClasses.size >= 3000)
        assertTrue(
            "Calls Android 10 can't verify, outside a nested `object ApiNN` (each soft-fails its whole class on the S1, " +
                "docs/research/art-start-path.md):\n${found.joinToString("\n")}",
            found.isEmpty(),
        )
    }

    @Test
    fun `no interface carries Kotlin's compatibility bridges`() {
        val found = appClasses.flatMap { (name, bytes) ->
            val methods = mutableListOf<String>()
            ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(access: Int, m: String, d: String?, s: String?, e: Array<out String>?): MethodVisitor? {
                    if (m.startsWith("access$") && m.endsWith("\$jd")) methods += "${name.replace('/', '.')}.$m"
                    return null
                }
            }, ClassReader.SKIP_CODE)
            methods
        }
        assertTrue("scanned only ${appClasses.size} classes", appClasses.size >= 3000)
        assertTrue(
            "Kotlin's interface-default bridges, which Android 10 verifies at every start. An app module builds with " +
                "jvmDefault = NO_COMPATIBILITY (build-logic/glassworks.android); a published library with DISABLE (the " +
                "root build.gradle.kts), never another mode, which would drop the DefaultImpls its users call:\n" +
                found.joinToString("\n"),
            found.isEmpty(),
        )
    }

    @Test
    fun `the check sees a direct call and a holder`() {
        // The database itself, against two calls this app makes: one Android 10 has, one it doesn't.
        assertTrue(api.level(Ref.Member("android/view/Window", "setDecorFitsSystemWindows", "(Z)V"))!! == 30)
        assertTrue(api.level(Ref.Member("android/widget/FrameLayout", "setStateDescription", "(Ljava/lang/CharSequence;)V"))!! == 30)
        assertTrue(api.level(Ref.Member("android/app/Activity", "getWindow", "()Landroid/view/Window;"))!! <= panelApi)
        assertTrue(api.level(Ref.Type("android/view/WindowInsets\$Type"))!! == 30)
        // MainActivity's own Android 11 calls sit in its holder.
        val holder = appClasses.keys.single { it == "dev/glassworks/MainActivity\$Api30" }
        assertTrue(references(appClasses.getValue(holder)).any { (level(it) ?: 0) > panelApi })
        // A call on an app class resolves through its superclasses: CssLineText is a TextView, so View's Android 11
        // setStateDescription on it is API 30, and its own onDraw override is the app's (no level).
        val css = "dev/glassworks/engine/vitro/cards/CssLineText"
        assertTrue(css in appClasses)
        assertEquals(30, level(Ref.Member(css, "setStateDescription", "(Ljava/lang/CharSequence;)V")))
        assertEquals(28, level(Ref.Member(css, "setFallbackLineSpacing", "(Z)V")))
        assertNull(level(Ref.Member(css, "onDraw", "(Landroid/graphics/Canvas;)V")))
    }

    @Test
    fun `the check sees types in signatures, fields, supertypes and lambdas`() {
        // A made-up class: a View subclass that calls Android 11's setStateDescription on itself, implements Android
        // 13's OnBackInvokedCallback, keeps a SoftApConfiguration (11) in a field, takes a WindowInsetsController (11),
        // and makes a lambda whose interface is Android 13's OnBackInvokedCallback. Every one of them counts.
        val w = ClassWriter(0)
        w.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "dev/glassworks/MadeUp", null, "android/widget/FrameLayout",
            arrayOf("android/window/OnBackInvokedCallback"))
        w.visitField(Opcodes.ACC_PRIVATE, "ap", "Landroid/net/wifi/SoftApConfiguration;", null, null).visitEnd()
        w.visitMethod(Opcodes.ACC_PUBLIC, "bars", "(Landroid/view/WindowInsetsController;)V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitInsn(Opcodes.ACONST_NULL)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "dev/glassworks/MadeUp", "setStateDescription", "(Ljava/lang/CharSequence;)V", false)
            visitInvokeDynamicInsn("onBackInvoked", "()Landroid/window/OnBackInvokedCallback;",
                Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                    "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false))
            visitInsn(Opcodes.POP)
            visitInsn(Opcodes.RETURN)
            visitMaxs(2, 2)
            visitEnd()
        }
        w.visitEnd()
        val bytes = w.toByteArray()
        val found = references(bytes).mapNotNull { ref ->
            level(ref, mapOf("dev/glassworks/MadeUp" to bytes))?.takeIf { it > panelApi }?.let { ref.shown }
        }.toSet()
        assertEquals(
            setOf(
                "dev.glassworks.MadeUp#setStateDescription", "android.window.OnBackInvokedCallback",
                "android.net.wifi.SoftApConfiguration", "android.view.WindowInsetsController",
            ),
            found,
        )
    }

    /** A class or member a method's code uses. */
    sealed class Ref {
        abstract val shown: String

        data class Type(val name: String) : Ref() {
            override val shown get() = name.replace('/', '.')
        }

        data class Member(val owner: String, val name: String, val desc: String) : Ref() {
            override val shown get() = "${owner.replace('/', '.')}#$name"
        }
    }

    /**
     * What [bytes]' class uses: its supertypes, its fields' and methods' types, and in its code the calls, fields and
     * their types, the types it creates, casts or catches, class constants, and a lambda's interface.
     */
    private fun references(bytes: ByteArray): Set<Ref> {
        val refs = linkedSetOf<Ref>()
        fun type(t: Type) {
            when (t.sort) {
                Type.OBJECT -> refs += Ref.Type(t.internalName)
                Type.ARRAY -> type(t.elementType)
                Type.METHOD -> { t.argumentTypes.forEach(::type); type(t.returnType) }
            }
        }
        fun owner(internalName: String) = type(Type.getObjectType(internalName))
        fun desc(d: String) = type(if (d.startsWith("(")) Type.getMethodType(d) else Type.getType(d))
        fun member(o: String, n: String, d: String) { owner(o); desc(d); refs += Ref.Member(o, n, d) }
        fun handle(h: Handle) = member(h.owner, h.name, h.desc)
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visit(v: Int, a: Int, n: String?, sig: String?, sup: String?, itfs: Array<out String>?) {
                sup?.let(::owner)
                itfs?.forEach(::owner)
            }
            override fun visitField(a: Int, n: String?, d: String, sig: String?, v: Any?): FieldVisitor? {
                desc(d)
                return null
            }
            override fun visitMethod(access: Int, m: String, d: String, s: String?, e: Array<out String>?): MethodVisitor {
                desc(d)
                return object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitMethodInsn(op: Int, o: String, n: String, desc: String, itf: Boolean) = member(o, n, desc)
                    override fun visitFieldInsn(op: Int, o: String, n: String, desc: String) = member(o, n, desc)
                    override fun visitTypeInsn(op: Int, t: String) = owner(t)
                    override fun visitMultiANewArrayInsn(desc: String, dims: Int) = type(Type.getType(desc))
                    override fun visitTryCatchBlock(start: Label?, end: Label?, handler: Label?, t: String?) { t?.let(::owner) }
                    override fun visitLdcInsn(v: Any?) {
                        when (v) {
                            is Type -> if (v.sort == Type.OBJECT || v.sort == Type.ARRAY) type(v)
                            is Handle -> handle(v)
                        }
                    }
                    override fun visitInvokeDynamicInsn(n: String?, d: String, bsm: Handle?, vararg args: Any?) {
                        desc(d) // its result is the lambda's interface
                        args.forEach {
                            when (it) {
                                is Handle -> handle(it)
                                is Type -> type(it)
                            }
                        }
                    }
                }
            }
        }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        return refs
    }

    /** A class's supertypes and its own members' keys (as the API database writes them), read from its bytes. */
    private class Shape(val supers: List<String>, val members: Set<String>)

    private val shapes = HashMap<String, Shape?>()

    /** [name]'s shape: from [extra]'s bytes, the app's, or any class on the test classpath (a library); null if none. */
    private fun shape(name: String, extra: Map<String, ByteArray>): Shape? {
        if (name !in extra) shapes[name]?.let { return it }
        val bytes = extra[name] ?: appClasses[name]
            ?: javaClass.classLoader?.getResourceAsStream("$name.class")?.use { it.readBytes() }
            ?: return null
        val supers = mutableListOf<String>()
        val members = HashSet<String>()
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visit(v: Int, a: Int, n: String?, sig: String?, sup: String?, itfs: Array<out String>?) {
                sup?.let(supers::add)
                itfs?.let(supers::addAll)
            }
            override fun visitField(a: Int, n: String, d: String?, sig: String?, v: Any?): FieldVisitor? {
                members += n
                return null
            }
            override fun visitMethod(a: Int, n: String, d: String, sig: String?, e: Array<out String>?): MethodVisitor? {
                members += n + d
                return null
            }
        }, ClassReader.SKIP_CODE)
        return Shape(supers, members).also { if (name !in extra) shapes[name] = it }
    }

    /**
     * The API level [ref] needs, or null when it isn't the platform's. A member named on a class that isn't the
     * platform's (the app's, a library's) is looked up through that class's supertypes, up to the class that declares
     * it: the app's own (null), or the platform's (its level). The platform's classes come from the API database
     * before the classpath, which holds Robolectric's whole framework, hidden APIs and all.
     */
    private fun level(ref: Ref, extra: Map<String, ByteArray> = emptyMap()): Int? = when (ref) {
        is Ref.Type -> api.level(ref)
        is Ref.Member -> inherited(ref, ref.owner, extra, HashSet())
    }

    private fun inherited(ref: Ref.Member, owner: String, extra: Map<String, ByteArray>, seen: MutableSet<String>): Int? {
        if (!seen.add(owner)) return null
        if (api.knows(owner)) return api.level(ref.copy(owner = owner))
        val s = shape(owner, extra) ?: return null
        val key = if (ref.desc.startsWith("(")) ref.name + ref.desc else ref.name
        if (key in s.members) return null
        return s.supers.firstNotNullOfOrNull { inherited(ref, it, extra, seen) }
    }

    /** The SDK's `api-versions.xml`: the level each class and member came in, and each class's supertypes. */
    class ApiDatabase private constructor(private val classes: Map<String, ApiClass>) {
        class ApiClass(val since: Int, val supers: List<String>, val members: Map<String, Int>)

        /** Whether [name] is one of the platform's public classes. */
        fun knows(name: String): Boolean = name in classes

        /** The level [ref] needs, or null when it isn't the platform's (the app's, a library's, a hidden API). */
        fun level(ref: Ref): Int? = when (ref) {
            is Ref.Type -> classes[ref.name]?.since
            is Ref.Member -> classes[ref.owner]?.let { c ->
                // A field's key is its name; a method's its name and descriptor, as the database writes them.
                val key = if (ref.desc.startsWith("(")) ref.name + ref.desc else ref.name
                member(ref.owner, key, mutableSetOf())?.let { maxOf(it, c.since) }
            }
        }

        private fun member(owner: String, key: String, seen: MutableSet<String>): Int? {
            if (!seen.add(owner)) return null
            val c = classes[owner] ?: return null
            c.members[key]?.let { return maxOf(it, c.since) }
            return c.supers.firstNotNullOfOrNull { member(it, key, seen) }
        }

        companion object {
            /** A `since` as its major level: `30`, or `36` for Android 16's minor release `36.1`. */
            private fun level(since: String?): Int? = since?.substringBefore('.')?.toInt()

            fun load(file: File): ApiDatabase {
                require(file.isFile) { "no API database at $file" }
                val classes = HashMap<String, ApiClass>(40_000)
                var name: String? = null
                var since = 1
                var supers = mutableListOf<String>()
                var members = HashMap<String, Int>()
                SAXParserFactory.newInstance().newSAXParser().parse(file, object : DefaultHandler() {
                    override fun startElement(uri: String?, local: String?, q: String, a: Attributes) {
                        when (q) {
                            "class" -> {
                                name = a.getValue("name")
                                since = level(a.getValue("since")) ?: 1
                                supers = mutableListOf()
                                members = HashMap()
                            }
                            "extends", "implements" -> if (name != null) supers += a.getValue("name")
                            "method", "field" -> if (name != null) {
                                members[a.getValue("name")] = level(a.getValue("since")) ?: since
                            }
                        }
                    }

                    override fun endElement(uri: String?, local: String?, q: String) {
                        if (q == "class") name?.let { classes[it] = ApiClass(since, supers, members) }.also { name = null }
                    }
                })
                require(classes.size > 1000) { "$file lists only ${classes.size} classes" }
                return ApiDatabase(classes)
            }
        }
    }
}
