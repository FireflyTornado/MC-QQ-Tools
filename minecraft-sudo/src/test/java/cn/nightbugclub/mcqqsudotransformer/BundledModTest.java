package cn.nightbugclub.mcqqsudotransformer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BundledModTest {
    @Test
    void resolvesTheArchiveAndDirectoryWithoutAnExistingZipFilesystem(@TempDir Path temp) throws Exception {
        Path jar = temp.resolve("bundled mod.jar");
        URI url = URI.create("jar:" + jar.toUri() + "!/META-INF/mcqqsudo/");
        var directory = BundledMod.jarDirectory(url);
        assertEquals("union", directory.archive().getScheme());
        assertEquals(jar, Path.of(URI.create(directory.archive().getRawSchemeSpecificPart())));
        assertEquals("/META-INF/mcqqsudo/", directory.entry());
    }
}
