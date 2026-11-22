package example.hello.mixins;

import com.google.gson.JsonObject;
import com.zenith.manager.state.State;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Changes ZenithHub's own code: every overview gets an extra field. */
@Mixin(targets = "com.zenith.manager.web.WebServer")
public abstract class OverviewMixin {
    @Inject(method = "overview", at = @At("RETURN"))
    private void hello$overview(State.User user, CallbackInfoReturnable<JsonObject> cir) {
        cir.getReturnValue().addProperty("helloMixin", "patched by the Hello plugin");
    }
}
