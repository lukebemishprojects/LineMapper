package dev.lukebemish.linemapper.jst;

import net.neoforged.jst.api.SourceTransformer;
import net.neoforged.jst.api.SourceTransformerPlugin;

public class LineMapperPlugin implements SourceTransformerPlugin {
    @Override
    public String getName() {
        return "linemapper";
    }

    @Override
    public SourceTransformer createTransformer() {
        return new LineMapperTransformer();
    }
}
