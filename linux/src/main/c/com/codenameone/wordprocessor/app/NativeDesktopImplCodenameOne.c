// The native menu bar, About panel and window sizing are Mac-only (AppKit); this port uses the
// framework's command menus and window defaults, so the interface reports itself unsupported.

#include "cn1_globals.h"

JAVA_BOOLEAN com_codenameone_wordprocessor_app_NativeDesktopImplCodenameOne_install___java_lang_String_R_boolean(CODENAME_ONE_THREAD_STATE, JAVA_OBJECT spec) {
    return JAVA_FALSE;
}

JAVA_VOID com_codenameone_wordprocessor_app_NativeDesktopImplCodenameOne_check___int_boolean(CODENAME_ONE_THREAD_STATE, JAVA_INT id, JAVA_BOOLEAN on) {
}

JAVA_VOID com_codenameone_wordprocessor_app_NativeDesktopImplCodenameOne_about___java_lang_String_java_lang_String(CODENAME_ONE_THREAD_STATE, JAVA_OBJECT version, JAVA_OBJECT credits) {
}

JAVA_VOID com_codenameone_wordprocessor_app_NativeDesktopImplCodenameOne_fitWindow___int_int(CODENAME_ONE_THREAD_STATE, JAVA_INT widthPercent, JAVA_INT heightPercent) {
}

JAVA_BOOLEAN com_codenameone_wordprocessor_app_NativeDesktopImplCodenameOne_isSupported___R_boolean(CODENAME_ONE_THREAD_STATE) {
    return JAVA_FALSE;
}
