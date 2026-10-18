#import <Foundation/Foundation.h>

@interface com_codenameone_wordprocessor_app_NativeFilesImpl : NSObject {
}

-(NSString*)saveAs:(NSString*)name param1:(NSString*)mime param2:(NSData*)data;
-(NSString*)writeFile:(NSString*)location param1:(NSData*)data;
-(BOOL)isSupported;
@end
