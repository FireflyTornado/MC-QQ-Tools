package cn.nightbugclub.mcqqsudotransformer;

import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;

import java.lang.System.Logger.Level;
import java.util.List;
import java.util.Set;

public final class ExecutorTransformationService implements ITransformationService {
    private static final System.Logger LOG = System.getLogger("MCQQSudo-Transformer");
    private Set<String> classes = Set.of();

    @Override
    public String name() {
        return "mcqqsudo-executor-output";
    }

    @Override
    public void onLoad(IEnvironment environment, Set<String> services) {}

    @Override
    public void initialize(IEnvironment environment) {
        classes = environment.getProperty(IEnvironment.Keys.GAMEDIR.get())
                .map(directory -> ExecutorTransformer.scan(directory.resolve("mods"))).orElse(Set.of());
        LOG.log(Level.INFO, "MCQQSudo executor capture targets: {0} classes", classes.size());
    }

    @Override
    @SuppressWarnings("rawtypes")
    public List<ITransformer> transformers() {
        return classes.isEmpty() ? List.of() : List.of(new ExecutorTransformer(classes));
    }
}
