#import "com_codenameone_wordprocessor_app_NativeFilesImpl.h"
#import <TargetConditionals.h>
#if TARGET_OS_OSX
#import <AppKit/AppKit.h>
#else
#import <UIKit/UIKit.h>
#import "CodenameOne_GLViewController.h"

/* Receives the export picker's answer and wakes the waiting worker thread. */
@interface CN1WordExportDelegate : NSObject<UIDocumentPickerDelegate> {
@public
    NSString *location;
    dispatch_semaphore_t finished;
}
@end

@implementation CN1WordExportDelegate
-(void)documentPicker:(UIDocumentPickerViewController*)picker didPickDocumentsAtURLs:(NSArray<NSURL*>*)urls {
    location = [[[urls firstObject] absoluteString] copy];
    dispatch_semaphore_signal(finished);
}
-(void)documentPickerWasCancelled:(UIDocumentPickerViewController*)picker {
    dispatch_semaphore_signal(finished);
}
@end
#endif

@implementation com_codenameone_wordprocessor_app_NativeFilesImpl
/* Called on a CN1 worker: the dialog runs on the main thread while this thread waits. */
-(NSString*)saveAs:(NSString*)name param1:(NSString*)mime param2:(NSData*)data {
    if ([NSThread isMainThread]) return nil;
#if TARGET_OS_OSX
    __block NSString *location = nil;
    dispatch_semaphore_t finished = dispatch_semaphore_create(0);
    dispatch_async(dispatch_get_main_queue(), ^{
        NSSavePanel *panel = [NSSavePanel savePanel];
        panel.canCreateDirectories = YES;
        panel.extensionHidden = NO;
        panel.allowedFileTypes = @[[name pathExtension]];
        panel.nameFieldStringValue = name;
        NSWindow *owner = [NSApp keyWindow];
        if (owner == nil) owner = [NSApp mainWindow];
        if ([panel runModal] == NSModalResponseOK) location = [[panel.URL absoluteString] copy];
        [panel orderOut:nil];
        if (owner != nil && owner.visible) [owner makeKeyAndOrderFront:nil];
        dispatch_semaphore_signal(finished);
    });
    dispatch_semaphore_wait(finished, DISPATCH_TIME_FOREVER);
    dispatch_release(finished);
    if (location == nil) return nil;
    NSString *error = [self writeFile:location param1:data];
    if (error != nil) {
        [location release];
        return [@"error:" stringByAppendingString:error];
    }
    return [location autorelease];
#else
    /* The picker exports (copies) a file, so write it to a temporary directory first. */
    NSString *dir = [NSTemporaryDirectory() stringByAppendingPathComponent:[[NSUUID UUID] UUIDString]];
    [[NSFileManager defaultManager] createDirectoryAtPath:dir withIntermediateDirectories:YES attributes:nil error:nil];
    NSURL *temp = [NSURL fileURLWithPath:[dir stringByAppendingPathComponent:name]];
    NSError *writeError = nil;
    if (![data writeToURL:temp options:NSDataWritingAtomic error:&writeError])
        return [@"error:" stringByAppendingString:[writeError localizedDescription]];
    CN1WordExportDelegate *delegate = [[CN1WordExportDelegate alloc] init];
    delegate->finished = dispatch_semaphore_create(0);
    dispatch_async(dispatch_get_main_queue(), ^{
        UIDocumentPickerViewController *picker =
            [[UIDocumentPickerViewController alloc] initForExportingURLs:@[temp] asCopy:YES];
        picker.delegate = delegate;
        [[CodenameOne_GLViewController instance] presentViewController:picker animated:YES completion:nil];
        [picker release];
    });
    dispatch_semaphore_wait(delegate->finished, DISPATCH_TIME_FOREVER);
    dispatch_release(delegate->finished);
    [[NSFileManager defaultManager] removeItemAtPath:dir error:nil];
    NSString *location = [delegate->location autorelease];
    [delegate release];
    return location;
#endif
}

-(NSString*)writeFile:(NSString*)location param1:(NSData*)data {
    NSURL *url = [NSURL URLWithString:location];
    /* A location exported on iOS lies outside the sandbox; writing needs its security scope. */
    BOOL scoped = [url startAccessingSecurityScopedResource];
    NSError *error = nil;
    BOOL ok = [data writeToURL:url options:NSDataWritingAtomic error:&error];
    if (scoped) [url stopAccessingSecurityScopedResource];
    return ok ? nil : [error localizedDescription];
}

-(BOOL)isSupported {
    return YES;
}
@end
