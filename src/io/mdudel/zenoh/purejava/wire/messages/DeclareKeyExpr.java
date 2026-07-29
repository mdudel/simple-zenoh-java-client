package io.mdudel.zenoh.purejava.wire.messages;
import io.mdudel.zenoh.purejava.wire.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
/** Zenoh 1.9 DeclareKeyExpr body used to establish compact key mappings. */
public record DeclareKeyExpr(long id,int keyScope,String keySuffix,List<Extension> extensions){
 public static final int ID=0,FLAG_N=0x20,FLAG_Z=0x80;
 public DeclareKeyExpr{if(id<0||id>0xffffL)throw new IllegalArgumentException("id must fit in u16: "+id);if(keyScope<0||keyScope>0xffff)throw new IllegalArgumentException("keyScope must fit in u16: "+keyScope);extensions=extensions==null?List.of():List.copyOf(extensions);}
 void encode(WBuf w){boolean n=keySuffix!=null;w.u8(ID|(n?FLAG_N:0)|(!extensions.isEmpty()?FLAG_Z:0));w.varInt(id);w.varInt(keyScope);if(n)w.lenBytes(keySuffix.getBytes(StandardCharsets.UTF_8));if(!extensions.isEmpty())Extension.writeAll(extensions,w);}
 static DeclareKeyExpr decode(int h,RBuf r){long id=r.varInt(),s=r.varInt();if(s>0xffffL)throw new IllegalArgumentException("keyScope must fit in u16: "+s);String x=(h&FLAG_N)!=0?new String(r.lenBytes(),StandardCharsets.UTF_8):null;return new DeclareKeyExpr(id,(int)s,x,(h&FLAG_Z)!=0?Extension.readAll(r):List.of());}
}
