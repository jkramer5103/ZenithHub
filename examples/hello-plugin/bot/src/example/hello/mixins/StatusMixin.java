package example.hello.mixins;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Changes ZenithProxy's own code: the status the manager polls gets an extra field. */
@Mixin(targets = "com.zenith.control.ControlServer")
public abstract class StatusMixin {
    @Inject(method = "status", at = @At("RETURN"))
    private void hello$status(HttpExchange ex, CallbackInfoReturnable<JsonObject> cir) {
        cir.getReturnValue().addProperty("helloMixin", true);
    }
}
