package nortantis;

import nortantis.util.Assets;

import java.util.List;
import java.util.Locale;

public record NameLanguagePack(String locale, List<String> placeNames, List<String> personNames, List<String> regionNames,
		List<String> regionDisallowedSuffixes)
{
	public static NameLanguagePack fromSettings(MapSettings settings)
	{
		String locale = normalizeLocale(settings.nameLocale);
		if ("ru".equals(locale))
		{
			String books = Assets.getAssetsPath() + "/books/";
			return new NameLanguagePack(
					locale,
					Assets.readNameList(books + "ru_fantasy_place_names.txt"),
					Assets.readNameList(books + "ru_fantasy_person_names.txt"),
					Assets.readNameList(books + "ru_fantasy_region_names.txt"),
					Assets.readNameList(books + "ru_fantasy_region_disallowed_suffixes.txt"));
		}
		return new NameLanguagePack("en", List.of(), List.of(), List.of(), List.of());
	}

	public static String normalizeLocale(String value)
	{
		if (value == null || value.isBlank())
		{
			return "ru";
		}
		String language = value.trim().toLowerCase(Locale.ROOT).split("[-_]", 2)[0];
		return "ru".equals(language) || "en".equals(language) ? language : "en";
	}

	public boolean isRussian()
	{
		return "ru".equals(locale);
	}
}
