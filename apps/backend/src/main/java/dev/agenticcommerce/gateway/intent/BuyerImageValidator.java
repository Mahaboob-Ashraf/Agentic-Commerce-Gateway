package dev.agenticcommerce.gateway.intent;

import static dev.agenticcommerce.gateway.intent.VisualCommerceModels.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class BuyerImageValidator {
    public ValidatedImage validate(MultipartFile file){
        if(file==null||file.isEmpty())throw invalid("A JPEG, PNG, or WebP product image is required");
        if(file.getSize()>MAX_IMAGE_BYTES)throw new BuyerException("BUYER_IMAGE_TOO_LARGE",HttpStatus.PAYLOAD_TOO_LARGE,"Product images must be 5 MB or smaller");
        byte[] bytes;try{bytes=file.getBytes();}catch(java.io.IOException e){throw invalid("The product image could not be read");}
        Detected detected=detect(bytes);
        String declared=file.getContentType()==null?"":file.getContentType().toLowerCase(Locale.ROOT).split(";",2)[0].strip();
        if(!declared.equals(detected.mimeType()))throw invalid("Image content does not match its declared JPEG, PNG, or WebP type");
        if(detected.width()<1||detected.height()<1||detected.width()>MAX_IMAGE_DIMENSION||detected.height()>MAX_IMAGE_DIMENSION
                ||(long)detected.width()*detected.height()>MAX_IMAGE_PIXELS)
            throw invalid("Product image dimensions exceed the supported limit");
        String filename=file.getOriginalFilename();if(filename!=null){filename=filename.replace('\\','/');filename=filename.substring(filename.lastIndexOf('/')+1).strip();
            if(filename.length()>255)filename=filename.substring(filename.length()-255);if(filename.isEmpty())filename=null;}
        return new ValidatedImage(bytes,detected.mimeType(),filename,bytes.length,detected.width(),detected.height(),sha256(bytes));
    }

    static Detected detect(byte[] bytes){
        if(bytes.length>=24&&u(bytes,0)==0x89&&ascii(bytes,1,3).equals("PNG")&&u(bytes,4)==13&&u(bytes,5)==10
                &&ascii(bytes,12,4).equals("IHDR"))return new Detected("image/png",be32(bytes,16),be32(bytes,20));
        if(bytes.length>=4&&u(bytes,0)==0xff&&u(bytes,1)==0xd8){
            int offset=2;while(offset+8<bytes.length){if(u(bytes,offset)!=0xff){offset++;continue;}int marker=u(bytes,offset+1);offset+=2;
                if(marker==0xd8||marker==0xd9)continue;if(offset+2>bytes.length)break;int length=(u(bytes,offset)<<8)|u(bytes,offset+1);
                if(length<2||offset+length>bytes.length)break;if(isJpegSof(marker)&&length>=7)return new Detected("image/jpeg",(u(bytes,offset+5)<<8)|u(bytes,offset+6),(u(bytes,offset+3)<<8)|u(bytes,offset+4));offset+=length;}
        }
        if(bytes.length>=30&&ascii(bytes,0,4).equals("RIFF")&&ascii(bytes,8,4).equals("WEBP")){
            String kind=ascii(bytes,12,4);
            if(kind.equals("VP8X"))return new Detected("image/webp",1+le24(bytes,24),1+le24(bytes,27));
            if(kind.equals("VP8 ")&&bytes.length>=30&&u(bytes,23)==0x9d&&u(bytes,24)==0x01&&u(bytes,25)==0x2a)
                return new Detected("image/webp",le16(bytes,26)&0x3fff,le16(bytes,28)&0x3fff);
            if(kind.equals("VP8L")&&u(bytes,20)==0x2f){int bits=ByteBuffer.wrap(bytes,21,4).order(ByteOrder.LITTLE_ENDIAN).getInt();return new Detected("image/webp",1+(bits&0x3fff),1+((bits>>>14)&0x3fff));}
        }
        throw invalid("Only valid JPEG, PNG, and WebP product images are supported");
    }
    private static boolean isJpegSof(int marker){return marker>=0xc0&&marker<=0xcf&&marker!=0xc4&&marker!=0xc8&&marker!=0xcc;}
    private static String ascii(byte[] bytes,int offset,int length){return new String(bytes,offset,length,StandardCharsets.US_ASCII);}
    private static int u(byte[] bytes,int offset){return bytes[offset]&0xff;}
    private static int be32(byte[] bytes,int offset){return ByteBuffer.wrap(bytes,offset,4).getInt();}
    private static int le16(byte[] bytes,int offset){return u(bytes,offset)|(u(bytes,offset+1)<<8);}
    private static int le24(byte[] bytes,int offset){return u(bytes,offset)|(u(bytes,offset+1)<<8)|(u(bytes,offset+2)<<16);}
    private static String sha256(byte[] bytes){try{return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException("SHA-256 is required by the JVM",impossible);}}
    private static BuyerException invalid(String message){return new BuyerException("BUYER_IMAGE_INVALID",HttpStatus.BAD_REQUEST,message);}
    record Detected(String mimeType,int width,int height) {}
}
