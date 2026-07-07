package com.fangnai.hacker.client.screen.widget;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.Locale;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

public class RangeSliderButton extends AbstractSliderButton {
    private final Component label;
    private final double min;
    private final double max;
    private final double step;
    private final DoubleSupplier getter;
    private final DoubleConsumer setter;
    private final String suffix;

    public RangeSliderButton(int x, int y, int width, int height, Component label,
                             double min, double max, double step, String suffix,
                             DoubleSupplier getter, DoubleConsumer setter) {
        super(x, y, width, height, label, toSliderValue(getter.getAsDouble(), min, max));
        this.label = label;
        this.min = min;
        this.max = max;
        this.step = step;
        this.getter = getter;
        this.setter = setter;
        this.suffix = suffix;
        updateMessage();
    }

    @Override
    protected void updateMessage() {
        double value = snap(fromSliderValue(this.value));
        setMessage(Component.literal(label.getString() + ": " + String.format(Locale.ROOT, "%.1f", value) + suffix));
    }

    @Override
    protected void applyValue() {
        setter.accept(snap(fromSliderValue(this.value)));
    }

    public void refresh() {
        this.value = toSliderValue(getter.getAsDouble(), min, max);
        updateMessage();
    }

    private double fromSliderValue(double sliderValue) {
        return min + (max - min) * Mth.clamp(sliderValue, 0.0D, 1.0D);
    }

    private double snap(double value) {
        double snapped = Math.round(value / step) * step;
        return Mth.clamp(snapped, min, max);
    }

    private static double toSliderValue(double value, double min, double max) {
        if (max <= min) {
            return 0.0D;
        }
        return Mth.clamp((value - min) / (max - min), 0.0D, 1.0D);
    }
}
