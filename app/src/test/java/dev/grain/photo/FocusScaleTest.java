package dev.grain.photo;
import org.junit.Test;
import static org.junit.Assert.*;

public class FocusScaleTest {
  @Test public void theFarEndIsInfinity(){assertTrue(Float.isInfinite(FocusScale.metres(0f,10f)));assertTrue(Float.isInfinite(FocusScale.metres(0f,0f)));}
  @Test public void theNearEndIsTheClosestFocus(){assertEquals(0.1f,FocusScale.metres(1f,10f),1e-4f);}
  @Test public void dioptresAreTheReciprocalOfMetres(){assertEquals(1f,FocusScale.metres(0.1f,10f),1e-4f);assertEquals(0.5f,FocusScale.metres(0.2f,10f),1e-4f);}
  @Test public void aLensWithoutAFocusDistanceStaysAtInfinity(){assertEquals(0f,FocusScale.diopters(1f,0f),1e-6f);assertTrue(Float.isInfinite(FocusScale.metres(1f,0f)));}
  @Test public void theFractionIsClamped(){assertEquals(0f,FocusScale.clampFraction(-2f),1e-6f);assertEquals(1f,FocusScale.clampFraction(4f),1e-6f);assertEquals(0f,FocusScale.clampFraction(Float.NaN),1e-6f);}
  @Test public void labelsReadLikeALensBarrel(){assertEquals("∞",FocusScale.label(0f,10f));assertEquals("1 m",FocusScale.label(0.1f,10f));assertEquals("0.50 m",FocusScale.label(0.2f,10f));assertEquals("0.10 m",FocusScale.label(1f,10f));}
  @Test public void labelsUseCentimetresBelowTenCentimetres(){assertEquals("5.0 cm",FocusScale.label(1f,20f));}
  @Test public void theStopsAreSpacedByDioptres(){assertEquals(5,FocusScale.stopCount(10f));assertEquals(0f,FocusScale.stopFraction(0,10f),1e-6f);assertEquals(0.1f,FocusScale.stopFraction(1,10f),1e-6f);assertEquals(0.2f,FocusScale.stopFraction(2,10f),1e-6f);assertEquals(0.5f,FocusScale.stopFraction(3,10f),1e-6f);assertEquals(1f,FocusScale.stopFraction(4,10f),1e-6f);}
  @Test public void onlyTheReachableStopsAreEngraved(){assertEquals(3,FocusScale.stopCount(2f));assertEquals(0.5f,FocusScale.stopFraction(1,2f),1e-6f);assertEquals(1f,FocusScale.stopFraction(2,2f),1e-6f);}
  @Test public void aLensWithNoFocusDistanceStillHasInfinity(){assertEquals(1,FocusScale.stopCount(0f));assertEquals("∞",FocusScale.stopLabel(0,0f));}
  @Test public void everyStopLabelIsReadable(){for(int i=0;i<FocusScale.stopCount(10f);i++){String l=FocusScale.stopLabel(i,10f);assertNotNull(l);assertTrue(l.length()>0);}}
}
