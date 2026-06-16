package nortantis;

import nortantis.geom.IntDimension;
import nortantis.swing.translation.Translation;

/**
 * The allowed map dimensions for generated backgrounds.
 */
public enum GeneratedDimension
{
	Square(4096, 4096), Sixteen_by_9(4096, 2304), Golden_Ratio(4096, 2531), Custom(0, 0);

	public final int width;
	public final int height;

	/**
	 * The longer side shared by every preset dimension. Arbitrary dimensions are normalized to this scale before being matched against the
	 * presets (see {@link #normalizeToPresetScale}).
	 */
	public static final int PRESET_LONG_SIDE = 4096;

	/**
	 * Maximum allowed aspect ratio (width:height or height:width). Aspect ratios more extreme than this are rejected by map generation.
	 */
	public static final int MAX_ASPECT_RATIO = 10;

	GeneratedDimension(int width, int height)
	{
		this.width = width;
		this.height = height;
	}

	public String displayName()
	{
		return Translation.get("GeneratedDimension." + name());
	}

	public double aspectRatio()
	{
		if (height == 0)
		{
			return 0;
		}
		return (double) width / height;
	}

	@Override
	public String toString()
	{
		if (this == Custom)
		{
			return displayName();
		}
		return displayName() + " (" + width + " \u00d7 " + height + ")";
	}

	public static GeneratedDimension fromDimensions(int w, int h)
	{
		for (GeneratedDimension d : values())
		{
			if (d == Custom)
			{
				continue;
			}
			if (d.width == w && d.height == h)
			{
				return d;
			}
		}
		return Custom;
	}

	/**
	 * Scales (width, height) so its longer side equals {@link #PRESET_LONG_SIDE} (rounding the shorter side), matching the scale at which
	 * preset dimensions are defined. This is the single normalization used both to produce the generated dimensions for a custom aspect
	 * ratio and to classify arbitrary dimensions by aspect ratio (see {@link #fromAspectRatio}).
	 */
	public static IntDimension normalizeToPresetScale(int width, int height)
	{
		if (width >= height)
		{
			return new IntDimension(PRESET_LONG_SIDE, Math.max(1, (int) Math.round((double) PRESET_LONG_SIDE * height / width)));
		}
		return new IntDimension(Math.max(1, (int) Math.round((double) PRESET_LONG_SIDE * width / height)), PRESET_LONG_SIDE);
	}

	/**
	 * Returns the preset matching the given width:height aspect ratio (in either orientation), or {@link #Custom} if none match. Used to
	 * label a selection box (or sub-map) by its aspect ratio. Matching is exact and as precise as {@link #fromDimensions} applied to a
	 * generated map: the dimensions are normalized to the preset scale and looked up, so a ratio that is merely close to a preset normalizes
	 * to a non-preset size and reads as Custom. Matching is orientation-independent (the longer side is always normalized to
	 * {@link #PRESET_LONG_SIDE}), so a rotated (portrait) selection still maps to its named ratio.
	 */
	public static GeneratedDimension fromAspectRatio(double width, double height)
	{
		if (width <= 0 || height <= 0)
		{
			return Custom;
		}
		IntDimension normalized = normalizeToPresetScale((int) Math.round(Math.max(width, height)), (int) Math.round(Math.min(width, height)));
		return fromDimensions(normalized.width, normalized.height);
	}

	/**
	 * Returns all preset dimensions — all values except {@link #Custom}.
	 */
	public static GeneratedDimension[] presets()
	{
		GeneratedDimension[] all = values();
		GeneratedDimension[] result = new GeneratedDimension[all.length - 1];
		int j = 0;
		for (GeneratedDimension d : all)
		{
			if (d != Custom)
			{
				result[j++] = d;
			}
		}
		return result;
	}
}
