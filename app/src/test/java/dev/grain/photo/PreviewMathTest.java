package dev.grain.photo;
import org.junit.Test;
import static org.junit.Assert.*;
public class PreviewMathTest {
  @Test public void portraitRearDoesNotRotateSensorTwice(){float[] p=PreviewMath.destinationCorners(300,400,1440,1080,90,0,1);assertArrayEquals(new float[]{0,0,300,0,300,400,0,400},p,.01f);}
  @Test public void portraitFrontDoesNotRotateSensorTwice(){float[] p=PreviewMath.destinationCorners(300,400,1440,1080,270,0,1);assertArrayEquals(new float[]{0,0,300,0,300,400,0,400},p,.01f);}
  @Test public void squarePreviewCropsWithoutStretch(){float[] p=PreviewMath.destinationCorners(300,300,1440,1080,90,0,1);assertArrayEquals(new float[]{0,-50,300,-50,300,350,0,350},p,.01f);}
  @Test public void digitalZoomScalesAboutCenter(){float[] p=PreviewMath.destinationCorners(300,400,1440,1080,90,0,2);assertArrayEquals(new float[]{-150,-200,450,-200,450,600,-150,600},p,.01f);}
  @Test public void upsideDownDisplayGetsOnlyItsOwnRotation(){float[] p=PreviewMath.destinationCorners(300,400,1440,1080,90,180,1);assertArrayEquals(new float[]{300,400,0,400,0,0,300,0},p,.01f);}
  @Test public void landscapeDisplayPreservesCenter(){float[] p=PreviewMath.destinationCorners(400,300,1440,1080,90,90,1);for(int i=0;i<4;i++){assertEquals(400,p[i*2]+p[((i+2)%4)*2],.01f);assertEquals(300,p[i*2+1]+p[((i+2)%4)*2+1],.01f);}}
  @Test public void readBackIsHeldOffWhileThePreviewSettles(){assertFalse(PreviewMath.canReadBack(1000,1500,true,100,100));assertFalse(PreviewMath.canReadBack(1499,1500,true,100,100));assertTrue(PreviewMath.canReadBack(1500,1500,true,100,100));}
  @Test public void readBackNeedsASurface(){assertFalse(PreviewMath.canReadBack(2000,0,false,100,100));}
  @Test public void readBackNeedsASizedView(){assertFalse(PreviewMath.canReadBack(2000,0,true,0,100));assertFalse(PreviewMath.canReadBack(2000,0,true,100,0));}
  @Test public void readBackRejectsABitmapSmallerThanThePixelsWeWant(){assertTrue(PreviewMath.fitsTarget(160,120,160,120));assertFalse(PreviewMath.fitsTarget(159,120,160,120));assertFalse(PreviewMath.fitsTarget(160,120,160,121));assertFalse(PreviewMath.fitsTarget(0,0,160,120));}
  @Test public void aBiggerBitmapStillFits(){assertTrue(PreviewMath.fitsTarget(1920,1200,160,120));}
}
