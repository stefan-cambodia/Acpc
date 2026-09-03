package dev.stefan.acpc.network

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RemoteCatalogTest {

    @Test
    fun `archive org metadata lists zipped discs with sizes and encoded urls`() {
        val json = """
            {"files":[
              {"name":"007 - A View to a Kill (1985)(Domark).zip","size":"46529","format":"ZIP"},
              {"name":"Sorcery (1985)(Virgin)[a].dsk","size":"194816","format":"Unknown"},
              {"name":"AmstradCPC_meta.xml","size":"1234","format":"Metadata"},
              {"name":"extras/Manual.pdf","size":"10","format":"PDF"},
              {"name":"extras/Bonus.dsk","size":"20"}
            ]}
        """.trimIndent()
        val files = RemoteCatalog.parseArchiveMetadata(json, "Coll", "")
        assertEquals(listOf("extras", "007 - A View to a Kill (1985)(Domark).zip", "Sorcery (1985)(Virgin)[a].dsk"), files.map { it.name })
        assertTrue(files[0].isDirectory)
        assertEquals("https://archive.org/download/Coll/extras/", files[0].url)
        assertEquals(46529L, files[1].size)
        assertEquals("https://archive.org/download/Coll/007%20-%20A%20View%20to%20a%20Kill%20%281985%29%28Domark%29.zip", files[1].url)
        assertEquals("https://archive.org/download/Coll/Sorcery%20%281985%29%28Virgin%29%5Ba%5D.dsk", files[2].url)
    }

    @Test
    fun `archive org sub directory only lists its own content`() {
        val json = """{"files":[{"name":"extras/Bonus.dsk","size":"20"},{"name":"Top.dsk","size":"1"}]}"""
        val files = RemoteCatalog.parseArchiveMetadata(json, "Coll", "extras")
        assertEquals(listOf("Bonus.dsk"), files.map { it.name })
        assertEquals("https://archive.org/download/Coll/extras/Bonus.dsk", files[0].url)
    }

    @Test
    fun `html index keeps discs and folders, drops parent sort and external links`() {
        val html = """
            <html><body><h1>Index of /cpc/</h1>
            <a href="../">Parent Directory</a>
            <a href="?C=M;O=D">Last modified</a>
            <a href="Game%20One%20%281985%29.dsk">Game One (1985).dsk</a>
            <a href='Game%20Two.ZIP'>Game Two.ZIP</a>
            <a href="readme.txt">readme.txt</a>
            <a href="sub%20dir/">sub dir/</a>
            <a href="/other/Elsewhere.dsk">elsewhere</a>
            <a href="https://example.org/x.dsk">external</a>
            <a href="Game%20One%20%281985%29.dsk">duplicate</a>
            </body></html>
        """.trimIndent()
        val files = RemoteCatalog.parseHtmlIndex(html, "http://server.local/cpc")
        assertEquals(listOf("sub dir", "Game One (1985).dsk", "Game Two.ZIP"), files.map { it.name })
        assertTrue(files[0].isDirectory)
        assertEquals("http://server.local/cpc/sub%20dir/", files[0].url)
        assertFalse(files[1].isDirectory)
        assertEquals("http://server.local/cpc/Game%20One%20%281985%29.dsk", files[1].url)
        assertEquals(-1L, files[1].size)
    }

    @Test
    fun `looksLikeFile recognises disc urls only`() {
        assertTrue(RemoteCatalog.looksLikeFile("https://h/x/Game.DSK"))
        assertTrue(RemoteCatalog.looksLikeFile("https://h/x/Game%20%281985%29.zip?dl=1"))
        assertFalse(RemoteCatalog.looksLikeFile("https://archive.org/download/AmstradCPCGameCollectionByGhostware"))
        assertFalse(RemoteCatalog.looksLikeFile("https://h/dir/"))
    }
}
