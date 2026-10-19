#import <Foundation/Foundation.h>

@interface com_codenameone_wordprocessor_app_NativeDesktopImpl : NSObject {
}

-(BOOL)install:(NSString*)spec;
-(void)check:(int)param param1:(BOOL)param1;
-(void)about:(NSString*)version param1:(NSString*)credits;
-(void)fitWindow:(int)param param1:(int)param1;
-(BOOL)isSupported;
@end
