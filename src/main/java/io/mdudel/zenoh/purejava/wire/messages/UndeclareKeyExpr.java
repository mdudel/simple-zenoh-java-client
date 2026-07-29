package io.mdudel.zenoh.purejava.wire.messages;
import io.mdudel.zenoh.purejava.wire.*;
import java.util.List;
/** Zenoh 1.9 UndeclareKeyExpr body. */
public record UndeclareKeyExpr(long id,List<Extension> extensions){
 public static final int ID=1,FLAG_Z=0x80;
 public UndeclareKeyExpr{if(id<0||id>0xffffL)throw new IllegalArgumentException("id must fit in u16: "+id);extensions=extensions==null?List.of():List.copyOf(extensions);}
 void encode(WBuf w){w.u8(ID|(!extensions.isEmpty()?FLAG_Z:0));w.varInt(id);if(!extensions.isEmpty())Extension.writeAll(extensions,w);}
 static UndeclareKeyExpr decode(int h,RBuf r){return new UndeclareKeyExpr(r.varInt(),(h&FLAG_Z)!=0?Extension.readAll(r):List.of());}
}
