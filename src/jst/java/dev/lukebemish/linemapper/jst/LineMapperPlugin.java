package dev.lukebemish.linemapper.jst;

import com.google.auto.service.AutoService;
import net.neoforged.jst.api.SourceTransformer;
import net.neoforged.jst.api.SourceTransformerPlugin;

@AutoService(SourceTransformerPlugin.class)
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
