// The Windows Save dialog (GetSaveFileNameW) for the native Windows port, owned by the
// application window and run on the calling worker thread; the thread state is yielded while
// it is open so the GC is never held up.

#include "cn1_globals.h"
#include "cn1_windows.h"
#include <commdlg.h>
#include <stdlib.h>
#include <string.h>
#include <wchar.h>

#pragma comment(lib, "comdlg32.lib")

static JAVA_OBJECT cn1WordJavaString(CODENAME_ONE_THREAD_STATE, const char* prefix, const WCHAR* wide) {
    int size = WideCharToMultiByte(CP_UTF8, 0, wide, -1, NULL, 0, NULL, NULL);
    size_t prefixLength = strlen(prefix);
    char* utf8 = (char*) malloc(prefixLength + (size_t) size);
    JAVA_OBJECT result;
    memcpy(utf8, prefix, prefixLength);
    WideCharToMultiByte(CP_UTF8, 0, wide, -1, utf8 + prefixLength, size, NULL, NULL);
    result = newStringFromCString(threadStateData, utf8);
    free(utf8);
    return result;
}

static const WCHAR* cn1WordWrite(const WCHAR* path, JAVA_OBJECT data) {
    DWORD length = (DWORD) (*(JAVA_ARRAY) data).length, written = 0;
    BOOL ok;
    HANDLE file = CreateFileW(path, GENERIC_WRITE, 0, NULL, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
    if (file == INVALID_HANDLE_VALUE) return L"The file could not be opened for writing";
    ok = WriteFile(file, CN1_ARRAY_DATA(data), length, &written, NULL) && written == length;
    return CloseHandle(file) && ok ? NULL : L"The file could not be written";
}

JAVA_OBJECT com_codenameone_wordprocessor_app_NativeFilesImplCodenameOne_saveAs___java_lang_String_java_lang_String_byte_1ARRAY_R_java_lang_String(CODENAME_ONE_THREAD_STATE, JAVA_OBJECT name, JAVA_OBJECT mime, JAVA_OBJECT data) {
    WCHAR path[MAX_PATH], filter[64];
    WCHAR* suggested = cn1WinJavaStringToWide(threadStateData, name, NULL);
    const WCHAR* extension = suggested != NULL ? wcsrchr(suggested, L'.') : NULL;
    const WCHAR* error;
    OPENFILENAMEW ofn;
    BOOL ok;
    if (suggested == NULL) return JAVA_NULL;
    wcsncpy(path, suggested, MAX_PATH - 1);
    path[MAX_PATH - 1] = 0;
    /* "*.docx\0*.docx\0\0": a filter block for the document's own type. */
    ZeroMemory(filter, sizeof(filter));
    if (extension != NULL && wcslen(extension) < 28) {
        swprintf(filter, 32, L"*%ls", extension);
        swprintf(filter + wcslen(filter) + 1, 32, L"*%ls", extension);
    }
    ZeroMemory(&ofn, sizeof(ofn));
    ofn.lStructSize = sizeof(ofn);
    ofn.hwndOwner = cn1Win.hwnd;
    ofn.lpstrFile = path;
    ofn.nMaxFile = MAX_PATH;
    ofn.lpstrFilter = extension != NULL && filter[0] ? filter : NULL;
    ofn.lpstrDefExt = extension != NULL ? extension + 1 : NULL;
    ofn.Flags = OFN_EXPLORER | OFN_NOCHANGEDIR | OFN_OVERWRITEPROMPT | OFN_PATHMUSTEXIST;
    CN1_YIELD_THREAD;
    ok = GetSaveFileNameW(&ofn);
    CN1_RESUME_THREAD;
    free(suggested);
    if (!ok) return JAVA_NULL;
    error = cn1WordWrite(path, data);
    return error != NULL ? cn1WordJavaString(threadStateData, "error:", error) : cn1WordJavaString(threadStateData, "", path);
}

JAVA_OBJECT com_codenameone_wordprocessor_app_NativeFilesImplCodenameOne_writeFile___java_lang_String_byte_1ARRAY_R_java_lang_String(CODENAME_ONE_THREAD_STATE, JAVA_OBJECT location, JAVA_OBJECT data) {
    WCHAR* path = cn1WinJavaStringToWide(threadStateData, location, NULL);
    const WCHAR* error = path != NULL ? cn1WordWrite(path, data) : L"No file location";
    free(path);
    return error == NULL ? JAVA_NULL : cn1WordJavaString(threadStateData, "", error);
}

JAVA_BOOLEAN com_codenameone_wordprocessor_app_NativeFilesImplCodenameOne_isSupported___R_boolean(CODENAME_ONE_THREAD_STATE) {
    return cn1Win.hwnd != NULL ? JAVA_TRUE : JAVA_FALSE;
}
