package dev.glassworks.s1

import org.junit.Assert.assertTrue
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
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
 *   `PlatformApi.Api30` does; this test lets an `ApiNN` class use APIs up to NN.
 * - is one of Kotlin's interface-default bridges (`access$<name>$jd`), which invoke-super the interface itself. The
 *   app's modules are built without them (`jvmDefault`, build-logic/glassworks.android); a library published to Maven
 *   keeps them for its users, so an interface there with a default body takes `@JvmDefaultWithoutCompatibility`.
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
            references(bytes).mapNotNull { ref -> api.level(ref)?.takeIf { it > allowed }?.let { "${name.replace('/', '.')}: ${ref.shown} (API $it)" } }
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
            "Kotlin's interface-default bridges, which Android 10 verifies at every start (a published library's " +
                "interface takes @JvmDefaultWithoutCompatibility):\n${found.joinToString("\n")}",
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
        assertTrue(references(appClasses.getValue(holder)).any { (api.level(it) ?: 0) > panelApi })
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

    /** What the code of [bytes]' methods references: calls, fields, types it creates, casts or catches, class constants. */
    private fun references(bytes: ByteArray): Set<Ref> {
        val refs = linkedSetOf<Ref>()
        fun type(t: Type) {
            when (t.sort) {
                Type.OBJECT -> refs += Ref.Type(t.internalName)
                Type.ARRAY -> type(t.elementType)
            }
        }
        fun owner(internalName: String) = type(Type.getObjectType(internalName))
        fun handle(h: Handle) { owner(h.owner); refs += Ref.Member(h.owner, h.name, h.desc) }
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(access: Int, m: String, d: String?, s: String?, e: Array<out String>?) =
                object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitMethodInsn(op: Int, o: String, n: String, desc: String, itf: Boolean) {
                        owner(o); refs += Ref.Member(o, n, desc)
                    }
                    override fun visitFieldInsn(op: Int, o: String, n: String, desc: String) {
                        owner(o); refs += Ref.Member(o, n, desc)
                    }
                    override fun visitTypeInsn(op: Int, t: String) = owner(t)
                    override fun visitMultiANewArrayInsn(desc: String, dims: Int) = type(Type.getType(desc))
                    override fun visitTryCatchBlock(start: Label?, end: Label?, handler: Label?, t: String?) { t?.let(::owner) }
                    override fun visitLdcInsn(v: Any?) {
                        when (v) {
                            is Type -> if (v.sort == Type.OBJECT || v.sort == Type.ARRAY) type(v)
                            is Handle -> handle(v)
                        }
                    }
                    override fun visitInvokeDynamicInsn(n: String?, desc: String?, bsm: Handle?, vararg args: Any?) {
                        args.forEach { if (it is Handle) handle(it) }
                    }
                }
        }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        return refs
    }

    /** The SDK's `api-versions.xml`: the level each class and member came in, and each class's supertypes. */
    class ApiDatabase private constructor(private val classes: Map<String, ApiClass>) {
        class ApiClass(val since: Int, val supers: List<String>, val members: Map<String, Int>)

        /** The level [ref] needs, or null when it isn't the platform's (the app's own, a library's, a hidden API). */
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
