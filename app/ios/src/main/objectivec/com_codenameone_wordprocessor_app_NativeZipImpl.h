#import <Foundation/Foundation.h>

@interface com_codenameone_wordprocessor_app_NativeZipImpl : NSObject {
}

-(NSData*)deflate:(NSData*)param;
-(int)crc32:(NSData*)param;
-(BOOL)isSupported;
@end
