package dev.glassworks

import dev.glassworks.testing.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LicencesTest {
    @Test
    fun listsNoticesFirstThenTextsBySpdxId() {
        val docs = Licences.documents(listOf("MPL-2.0.txt", "Apache-2.0.txt", "README", "0BSD.txt"))
        assertEquals(
            listOf("licenses/THIRD-PARTY-NOTICES.md", "licenses/LICENSE",
                "licenses/texts/0BSD.txt", "licenses/texts/Apache-2.0.txt", "licenses/texts/MPL-2.0.txt"),
            docs.map { it.path },
        )
        assertEquals(listOf("0BSD", "Apache-2.0", "MPL-2.0"), docs.drop(2).map { it.label })
        // The repo's own licence, named for the project, not one app: every app on the core shows it (#23).
        assertEquals("Glassworks license (MIT)", docs[1].label)
    }

    /** Every SPDX id the notices name must have its text in LICENSES/, or the app's screen would lack it. */
    @Test
    fun everyLicenceTheNoticesNameHasItsText() {
        val root = RepoFiles.root
        val texts = File(root, "LICENSES").list().orEmpty().map { it.removeSuffix(".txt") }.toSet()
        val notices = File(root, "THIRD-PARTY-NOTICES.md").readText()
        val needed = mapOf(
            "MIT" to "MIT", "Apache-2.0" to "Apache-2.0", "MPL-2.0" to "MPL-2.0", "0BSD" to "0BSD",
            "BSD-2-Clause" to "BSD-2-Clause", "BSD-3-Clause" to "BSD-3-Clause", "Zlib" to "Zlib",
            "GPL-2.0-or-later" to "GPL-2.0", "GPL-3.0-only" to "GPL-3.0", "GPL-3.0-or-later" to "GPL-3.0",
            "LGPL-2.1-or-later" to "LGPL-2.1", "GCC-exception-3.1" to "GCC-exception-3.1",
            "LicenseRef-Silabs-MSLA" to "LicenseRef-Silabs-MSLA",
            "OFL-1.1" to "OFL-1.1",
            // desugar_jdk_libs, in the generic app's legacy APK (#198) and the legacy and Vitro debug test apps.
            "GPL-2.0-only WITH Classpath-exception-2.0" to "GPL-2.0", "Classpath-exception-2.0" to "Classpath-exception-2.0",
            // libwebrtc's bundled libc++ and its smaller notices (libs/ha-webrtc, in both apps).
            "Apache-2.0 WITH LLVM-exception" to "LLVM-exception", "LicenseRef-libwebrtc-notices" to "LicenseRef-libwebrtc-notices",
            // Vitro's locale tables: Unicode CLDR data, in both APKs.
            "Unicode-3.0" to "Unicode-3.0",
        )
        for ((id, file) in needed) {
            assertTrue("notices should name $id", id in notices)
            assertTrue("LICENSES/$file.txt missing for $id", file in texts)
        }
    }
}
