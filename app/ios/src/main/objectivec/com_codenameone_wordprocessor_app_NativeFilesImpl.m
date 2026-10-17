#import "com_codenameone_wordprocessor_app_NativeFilesImpl.h"
#import <TargetConditionals.h>
#if TARGET_OS_OSX
#import <AppKit/AppKit.h>
#endif

@implementation com_codenameone_wordprocessor_app_NativeFilesImpl
-(NSString*)chooseSaveFile:(NSString*)name {
#if TARGET_OS_OSX
    __block NSString *path = nil;
    // Files.save calls this native interface on a worker. Keep AppKit's main
    // loop running while the native save dialog is open.
    dispatch_semaphore_t finished = dispatch_semaphore_create(0);
    void (^choose)(void) = ^{
        NSSavePanel *panel = [NSSavePanel savePanel];
        panel.canCreateDirectories = YES;
        panel.extensionHidden = NO;
        panel.allowedFileTypes = @[[name pathExtension]];
        panel.nameFieldStringValue = name;
        NSWindow *owner = [NSApp keyWindow];
        if (owner == nil) owner = [NSApp mainWindow];
        // Run AppKit's modal loop on its main thread. The Java caller remains
        // on its worker and waits below; document input cannot retake focus.
        NSModalResponse response = [panel runModal];
        if (response == NSModalResponseOK) path = [[panel.URL absoluteString] copy];
        [panel orderOut:nil];
        if (owner != nil && owner.visible) [owner makeKeyAndOrderFront:nil];
        dispatch_semaphore_signal(finished);
    };
    if ([NSThread isMainThread]) {
        dispatch_release(finished);
        return nil;
    }
    dispatch_async(dispatch_get_main_queue(), choose);
    dispatch_semaphore_wait(finished, DISPATCH_TIME_FOREVER);
    dispatch_release(finished);
    return [path autorelease];
#else
    return nil;
#endif
}
-(NSString*)writeFile:(NSString*)path param1:(NSData*)data {
    NSError *error = nil;
    NSURL *url = [NSURL URLWithString:path];
    if (![data writeToURL:url options:NSDataWritingAtomic error:&error]) return [error localizedDescription];
    return nil;
}
-(BOOL)isSupported {
#if TARGET_OS_OSX
    return YES;
#else
    return NO;
#endif
}
@end
