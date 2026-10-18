#import <Foundation/Foundation.h>

@interface com_codenameone_wordprocessor_app_NativeFilesImpl : NSObject {
}

-(NSString*)chooseSaveFile:(NSString*)param;
-(NSString*)writeFile:(NSString*)path param1:(NSData*)data;
-(BOOL)isSupported;
@end
