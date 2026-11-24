package cn.nightbugclub.mcqqsudotransformer;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

final class BundledMod {
    private static final String DIRECTORY = "/META-INF/mcqqsudo/";
    private static Path mountedRoot;

    private BundledMod() {}

    static synchronized Path root() {
        if (mountedRoot == null) {
            mountedRoot = mount();
        }
        return mountedRoot;
    }

    private static Path mount() {
        var resource = BundledMod.class.getResource(DIRECTORY);
        if (resource == null) {
            throw new IllegalStateException("Missing embedded MCQQSudo mod directory: " + DIRECTORY);
        }
        try {
            Path archive;
            try (var files = Files.list(Path.of(resource.toURI()))) {
                var jars = files.filter(path -> path.getFileName().toString().endsWith("-game.jar")).toList();
                if (jars.size() != 1) {
                    throw new IllegalStateException("Expected one embedded MCQQSudo mod in " + DIRECTORY);
                }
                archive = jars.get(0);
            }
            // Use the same nested archive provider as Forge's Jar-in-Jar locator.
            URI uri = URI.create("jij:" + archive.toAbsolutePath().toUri().getRawSchemeSpecificPart()).normalize();
            var filesystem = FileSystems.newFileSystem(uri, Map.of("packagePath", archive));
            Path root = filesystem.getPath("/");
            for (String entry : List.of("META-INF/mods.toml", ExecutorTransformer.BRIDGE + ".class",
                    StageCalls.BRIDGE + ".class")) {
                if (!Files.isRegularFile(root.resolve(entry))) {
                    filesystem.close();
                    throw new IllegalStateException("Embedded MCQQSudo mod is missing " + entry);
                }
            }
            // Forge reads classes and resources from this root throughout the server's lifetime.
            return root;
        } catch (IOException | URISyntaxException | RuntimeException failure) {
            throw new IllegalStateException("Could not mount the embedded MCQQSudo mod", failure);
        }
    }
}
