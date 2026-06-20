package nortantis;

import nortantis.platform.PlatformFactory;
import nortantis.platform.awt.AwtFactory;
import nortantis.util.Assets;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Benchmark comparing the two {@link WorldGraph#findClosestCenter} algorithms: the rasterized, resolution-dependent
 * {@code PIXEL_CACHED} lookup against the geometric, resolution-invariant {@code GRID_BASED} lookup.
 *
 * <p>The grid lookup is preferable for icon water-detection because it is resolution-invariant (so icons don't appear/disappear when the
 * display quality changes) and uses far less memory than the full-map pixel table, but it has historically been too slow per call. This
 * benchmark quantifies that gap.
 *
 * <p>Benchmarks are skipped during normal test runs. Run with:
 * <pre>./gradlew test --tests "nortantis.FindClosestCenterBenchmark" -DrunBenchmarks=true</pre>
 */
@EnabledIfSystemProperty(named = "runBenchmarks", matches = "true")
public class FindClosestCenterBenchmark
{
	@BeforeAll
	public static void setup()
	{
		PlatformFactory.setInstance(new AwtFactory());
		nortantis.swing.translation.Translation.initialize();
		Assets.disableAddedArtPacksForUnitTests();
	}

	@Test
	public void benchmarkAtNormalResolution() throws Exception
	{
		MapTestUtil.runFindClosestCenterBenchmark(1.0, 200_000, 5);
	}

	@Test
	public void benchmarkAtHighResolution() throws Exception
	{
		MapTestUtil.runFindClosestCenterBenchmark(1.5, 200_000, 5);
	}

	@Test
	public void benchmarkFullMapCreationByMode() throws Exception
	{
		MapTestUtil.runMapCreationModeComparisonBenchmark(1.0, 1, 3);
	}
}
