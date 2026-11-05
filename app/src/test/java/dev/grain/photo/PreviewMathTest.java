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
}
