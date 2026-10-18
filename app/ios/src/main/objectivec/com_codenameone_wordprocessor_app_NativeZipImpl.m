#import "com_codenameone_wordprocessor_app_NativeZipImpl.h"
#import <zlib.h>

@implementation com_codenameone_wordprocessor_app_NativeZipImpl
-(NSData*)deflate:(NSData*)data {
    z_stream s;
    memset(&s, 0, sizeof(s));
    // Negative window bits: raw deflate, as a ZIP entry stores it. Level 1 matches the Java codec.
    if (deflateInit2(&s, 1, Z_DEFLATED, -15, 8, Z_DEFAULT_STRATEGY) != Z_OK) return nil;
    uLong bound = deflateBound(&s, (uLong)data.length);
    NSMutableData *out = [NSMutableData dataWithLength:bound];
    s.next_in = (Bytef *)data.bytes;
    s.avail_in = (uInt)data.length;
    s.next_out = (Bytef *)out.mutableBytes;
    s.avail_out = (uInt)bound;
    int r = deflate(&s, Z_FINISH);
    deflateEnd(&s);
    if (r != Z_STREAM_END) return nil;
    out.length = s.total_out;
    return out;
}

-(int)crc32:(NSData*)data {
    return (int)crc32(0L, (const Bytef *)data.bytes, (uInt)data.length);
}

-(BOOL)isSupported {
    return YES;
}
@end
