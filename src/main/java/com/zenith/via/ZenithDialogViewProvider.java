package com.zenith.via;

import com.viaversion.nbt.tag.Tag;
import com.viaversion.viabackwards.protocol.v1_21_6to1_21_5.Protocol1_21_6To1_21_5;
import com.viaversion.viabackwards.protocol.v1_21_6to1_21_5.data.input.BooleanInput;
import com.viaversion.viabackwards.protocol.v1_21_6to1_21_5.data.input.NumberRangeInput;
import com.viaversion.viabackwards.protocol.v1_21_6to1_21_5.data.input.SingleOptionInput;
import com.viaversion.viabackwards.protocol.v1_21_6to1_21_5.data.widget.ItemWidget;
import com.viaversion.viabackwards.protocol.v1_21_6to1_21_5.provider.ChestDialogViewProvider;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.item.Item;

/**
 * ViaBackwards' chest view for 1.21.6 dialogs reads some labels with a text codec that doesn't know the
 * {@code custom} and {@code show_dialog} click events, before rewriting them, and the error disconnects us
 * (e.g. HugoSMP's /settings). Rewriting those labels first turns the click events into commands it understands.
 */
public class ZenithDialogViewProvider extends ChestDialogViewProvider {
    public ZenithDialogViewProvider(final Protocol1_21_6To1_21_5 protocol) {
        super(protocol);
    }

    @Override
    protected Item getMultiTextWidget(final UserConnection connection, final MultiTextWidget widget) {
        for (final Tag label : widget.labels()) handleTag(connection, label);
        return super.getMultiTextWidget(connection, widget);
    }

    @Override
    protected Item getItemWidget(final UserConnection connection, final ItemWidget widget) {
        if (widget.description() != null) handleTag(connection, widget.description().label());
        return super.getItemWidget(connection, widget);
    }

    @Override
    protected Item getBooleanInput(final UserConnection connection, final BooleanInput input) {
        handleTag(connection, input.label());
        return super.getBooleanInput(connection, input);
    }

    @Override
    protected Item getNumberRangeInput(final UserConnection connection, final NumberRangeInput input) {
        handleTag(connection, input.label());
        return super.getNumberRangeInput(connection, input);
    }

    @Override
    protected Item getSingleOptionInput(final UserConnection connection, final SingleOptionInput input) {
        handleTag(connection, input.label());
        return super.getSingleOptionInput(connection, input);
    }
}
