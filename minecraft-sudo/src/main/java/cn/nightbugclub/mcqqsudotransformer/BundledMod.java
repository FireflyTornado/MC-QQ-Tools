package cn.nightbugclub.mcqqsudotransformer;

import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

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
            return mount(resource.toURI());
        } catch (IOException | URISyntaxException | RuntimeException failure) {
            throw new IllegalStateException("Could not mount the embedded MCQQSudo mod", failure);
        }
    }

    private static Path mount(URI directory) throws IOException {
        Path archive;
        try (var files = Files.list(directoryPath(directory))) {
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
    }

    private static Path directoryPath(URI uri) throws IOException {
        if (!"jar".equals(uri.getScheme())) {
            return Path.of(uri);
        }
        var directory = jarDirectory(uri);
        // Forge's nested locator expects a union-backed path, even for a standard jar URL.
        BiPredicate<String, String> allEntries = (entry, base) -> true;
        var outer = FileSystems.newFileSystem(directory.archive(), Map.of("filter", allEntries));
        return outer.getPath(directory.entry());
    }

    record JarDirectory(URI archive, String entry) {}

    static JarDirectory jarDirectory(URI uri) throws IOException {
        var connection = (JarURLConnection) uri.toURL().openConnection();
        URI archive = URI.create("union:" + connection.getJarFileURL().toExternalForm());
        return new JarDirectory(archive, "/" + connection.getEntryName());
    }
}
