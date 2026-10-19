#import "com_codenameone_wordprocessor_app_NativeDesktopImpl.h"
#import <TargetConditionals.h>
#if TARGET_OS_OSX
#import <AppKit/AppKit.h>
#include "cn1_globals.h"
#include "com_codenameone_wordprocessor_app_NativeMenuCallback.h"

/* The port's default menu. Called first so its idempotent installer, which otherwise runs after
   the first frame, never replaces the menu bar built here. */
extern void CN1MacInstallMainMenu(void);

/* Forwards a selection to Java on the main thread; the Java side hops to the EDT. */
@interface CN1WordMenuTarget : NSObject
-(void)cn1WordMenu:(id)sender;
@end

@implementation CN1WordMenuTarget
-(void)cn1WordMenu:(id)sender {
    if (![sender isKindOfClass:[NSMenuItem class]]) return;
    com_codenameone_wordprocessor_app_NativeMenuCallback_fire___int(getThreadLocalData(),
            (JAVA_INT)((NSMenuItem *)sender).tag);
}
@end

static CN1WordMenuTarget *cn1WordTarget;

static NSMenuItem *cn1WordFind(NSMenu *menu, NSInteger tag) {
    for (NSMenuItem *item in menu.itemArray) {
        if (item.target == cn1WordTarget && item.tag == tag) return item;
        if (item.submenu != nil) {
            NSMenuItem *found = cn1WordFind(item.submenu, tag);
            if (found != nil) return found;
        }
    }
    return nil;
}

static void cn1WordKey(NSMenuItem *item, int key, int modifiers) {
    if (key == 0) return;
    NSEventModifierFlags flags = 0;
    if (modifiers & 1) flags |= NSEventModifierFlagCommand;
    if (modifiers & 2) flags |= NSEventModifierFlagShift;
    if (modifiers & 4) flags |= NSEventModifierFlagOption;
    /* Lowercase: AppKit reads an uppercase equivalent as implying Shift. */
    item.keyEquivalent = [[NSString stringWithFormat:@"%C", (unichar)key] lowercaseString];
    item.keyEquivalentModifierMask = flags;
}
#endif

@implementation com_codenameone_wordprocessor_app_NativeDesktopImpl

-(BOOL)install:(NSString*)spec {
#if TARGET_OS_OSX
    NSString *lines = [spec copy];
    dispatch_async(dispatch_get_main_queue(), ^{
        CN1MacInstallMainMenu();
        if (cn1WordTarget == nil) cn1WordTarget = [[CN1WordMenuTarget alloc] init];
        NSMenu *bar = [[NSMenu alloc] initWithTitle:@""];
        NSMutableArray *stack = [NSMutableArray arrayWithObject:bar];
        for (NSString *line in [lines componentsSeparatedByString:@"\n"]) {
            NSArray *f = [line componentsSeparatedByString:@"\t"];
            if (f.count < 6) continue;
            NSString *kind = f[0], *title = f[5];
            NSInteger depth = [f[1] integerValue];
            while ((NSInteger)stack.count > depth + 1) [stack removeLastObject];
            if ((NSInteger)stack.count != depth + 1) continue;
            NSMenu *parent = stack.lastObject;
            if ([kind isEqualToString:@"S"]) {
                [parent addItem:[NSMenuItem separatorItem]];
                continue;
            }
            NSMenuItem *item;
            if ([kind isEqualToString:@"A"]) {
                item = [[NSMenuItem alloc] initWithTitle:title action:NSSelectorFromString(f[2]) keyEquivalent:@""];
            } else if ([kind isEqualToString:@"I"]) {
                item = [[NSMenuItem alloc] initWithTitle:title action:@selector(cn1WordMenu:) keyEquivalent:@""];
                item.target = cn1WordTarget;
                item.tag = [f[2] integerValue];
                if (f.count > 6 && [f[6] isEqualToString:@"1"]) item.state = NSControlStateValueOn;
            } else {
                item = [[NSMenuItem alloc] initWithTitle:title action:NULL keyEquivalent:@""];
                NSMenu *submenu = [[NSMenu alloc] initWithTitle:title];
                item.submenu = submenu;
                if ([kind isEqualToString:@"V"]) [NSApp setServicesMenu:submenu];
                else if (depth == 0 && [title isEqualToString:@"Window"]) [NSApp setWindowsMenu:submenu];
                else if (depth == 0 && [title isEqualToString:@"Help"]) [NSApp setHelpMenu:submenu];
                if ([kind isEqualToString:@"M"]) [stack addObject:submenu];
                [submenu release];
            }
            cn1WordKey(item, [f[3] intValue], [f[4] intValue]);
            [parent addItem:item];
            [item release];
        }
        [NSApp setMainMenu:bar];
        [bar release];
        /* A Window menu handed over late lists only windows opened after it. */
        for (NSWindow *w in [NSApp windows])
            if (w.isVisible && !w.isExcludedFromWindowsMenu)
                [NSApp changeWindowsItem:w title:(w.title != nil ? w.title : @"") filename:NO];
    });
    [lines release];
    return YES;
#else
    return NO;
#endif
}

-(void)check:(int)param param1:(BOOL)param1 {
#if TARGET_OS_OSX
    dispatch_async(dispatch_get_main_queue(), ^{
        NSMenuItem *item = cn1WordFind([NSApp mainMenu], param);
        if (item != nil) item.state = param1 ? NSControlStateValueOn : NSControlStateValueOff;
    });
#endif
}

-(void)about:(NSString*)version param1:(NSString*)credits {
#if TARGET_OS_OSX
    NSString *v = [version copy], *c = [credits copy];
    dispatch_async(dispatch_get_main_queue(), ^{
        NSData *html = [c dataUsingEncoding:NSUTF8StringEncoding];
        NSMutableAttributedString *text = [[NSMutableAttributedString alloc] initWithHTML:html documentAttributes:nil];
        /* HTML renders in black; the panel follows the system appearance. */
        if (text != nil) [text addAttribute:NSForegroundColorAttributeName value:[NSColor labelColor]
                                      range:NSMakeRange(0, text.length)];
        NSMutableDictionary *options = [NSMutableDictionary dictionary];
        options[NSAboutPanelOptionApplicationVersion] = v;
        options[NSAboutPanelOptionVersion] = @"";
        if (text != nil) options[NSAboutPanelOptionCredits] = text;
        [NSApp activateIgnoringOtherApps:YES];
        [NSApp orderFrontStandardAboutPanelWithOptions:options];
        [text release];
    });
    [v release];
    [c release];
#endif
}

-(void)fitWindow:(int)param param1:(int)param1 {
#if TARGET_OS_OSX
    dispatch_async(dispatch_get_main_queue(), ^{
        NSWindow *w = [NSApp mainWindow];
        if (w == nil) w = [[NSApp windows] firstObject];
        NSScreen *screen = w.screen != nil ? w.screen : [NSScreen mainScreen];
        if (w == nil || screen == nil) return;
        NSRect area = screen.visibleFrame;
        NSRect frame = NSMakeRect(0, 0, floor(area.size.width * param / 100), floor(area.size.height * param1 / 100));
        frame.origin.x = area.origin.x + floor((area.size.width - frame.size.width) / 2);
        frame.origin.y = area.origin.y + floor((area.size.height - frame.size.height) / 2);
        [w setFrame:frame display:YES animate:NO];
    });
#endif
}

-(BOOL)isSupported {
#if TARGET_OS_OSX
    return YES;
#else
    return NO;
#endif
}

@end
