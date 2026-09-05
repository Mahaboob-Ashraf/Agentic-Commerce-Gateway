package dev.agenticcommerce.gateway.intent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class BuyerImageValidatorTest {
    private final BuyerImageValidator validator=new BuyerImageValidator();

    @Test void acceptsPngJpegAndWebpByDeclaredAndActualContent(){
        assertThat(validator.validate(file("reference.png","image/png",png(640,480))).mimeType()).isEqualTo("image/png");
        assertThat(validator.validate(file("reference.jpg","image/jpeg",jpeg(800,600))).width()).isEqualTo(800);
        assertThat(validator.validate(file("reference.webp","image/webp",webp(400,300))).height()).isEqualTo(300);
    }
    @Test void rejectsUnsupportedOrMismatchedContent(){
        assertThatThrownBy(()->validator.validate(file("unsafe.svg","image/svg+xml","<svg/>".getBytes())))
                .isInstanceOfSatisfying(BuyerException.class,e->assertThat(e.code()).isEqualTo("BUYER_IMAGE_INVALID"));
        assertThatThrownBy(()->validator.validate(file("fake.png","image/png",jpeg(20,20))))
                .isInstanceOfSatisfying(BuyerException.class,e->assertThat(e.code()).isEqualTo("BUYER_IMAGE_INVALID"));
    }
    @Test void rejectsOversizedUploadBeforeContentInterpretation(){byte[] bytes=new byte[(int)VisualCommerceModels.MAX_IMAGE_BYTES+1];
        assertThatThrownBy(()->validator.validate(file("large.png","image/png",bytes)))
                .isInstanceOfSatisfying(BuyerException.class,e->assertThat(e.code()).isEqualTo("BUYER_IMAGE_TOO_LARGE"));}
    @Test void rejectsExcessiveDecodedDimensions(){
        assertThatThrownBy(()->validator.validate(file("wide.png","image/png",png(8193,100))))
                .isInstanceOfSatisfying(BuyerException.class,e->assertThat(e.code()).isEqualTo("BUYER_IMAGE_INVALID"));}

    private static MockMultipartFile file(String name,String mime,byte[] bytes){return new MockMultipartFile("image",name,mime,bytes);}
    private static byte[] png(int width,int height){byte[] b=new byte[32];byte[] sig={(byte)0x89,0x50,0x4e,0x47,13,10,26,10};System.arraycopy(sig,0,b,0,8);
        b[12]='I';b[13]='H';b[14]='D';b[15]='R';ByteBuffer.wrap(b,16,8).putInt(width).putInt(height);return b;}
    private static byte[] jpeg(int width,int height){return new byte[]{(byte)0xff,(byte)0xd8,(byte)0xff,(byte)0xc0,0,7,8,(byte)(height>>>8),(byte)height,(byte)(width>>>8),(byte)width,(byte)0xff,(byte)0xd9};}
    private static byte[] webp(int width,int height){byte[] b=new byte[30];System.arraycopy("RIFF".getBytes(),0,b,0,4);System.arraycopy("WEBPVP8X".getBytes(),0,b,8,8);
        int w=width-1,h=height-1;b[24]=(byte)w;b[25]=(byte)(w>>>8);b[26]=(byte)(w>>>16);b[27]=(byte)h;b[28]=(byte)(h>>>8);b[29]=(byte)(h>>>16);return b;}
}
