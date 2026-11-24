package cn.nightbugclub.mcqqsudotransformer;

import net.minecraftforge.fml.loading.moddiscovery.AbstractJarFileModLocator;

import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

public final class BundledModLocator extends AbstractJarFileModLocator {
    @Override
    public String name() {
        return "MCQQSudo bundled mod";
    }

    @Override
    public void initArguments(Map<String, ?> arguments) {}

    @Override
    public Stream<Path> scanCandidates() {
        return Stream.of(BundledMod.root());
    }
}
