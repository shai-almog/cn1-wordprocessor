// The GTK Save dialog for the native Linux port. GTK calls run on the port's GTK thread
// through cn1LinuxRunOnMainAndWait, parented to the application window.

#include "cn1_globals.h"
#include "cn1_linux_gfx.h"
#include <stdio.h>
#include <string.h>

typedef struct { const char* name; char* chosen; } CN1WordSave;

static void cn1WordSaveDialog(void* p) {
    CN1WordSave* save = (CN1WordSave*) p;
    GtkWidget* dialog = gtk_file_chooser_dialog_new("Save", GTK_WINDOW(cn1LinuxWindowWidget()),
            GTK_FILE_CHOOSER_ACTION_SAVE, "_Cancel", GTK_RESPONSE_CANCEL, "_Save", GTK_RESPONSE_ACCEPT, NULL);
    gtk_file_chooser_set_do_overwrite_confirmation(GTK_FILE_CHOOSER(dialog), TRUE);
    gtk_file_chooser_set_current_name(GTK_FILE_CHOOSER(dialog), save->name);
    if (gtk_dialog_run(GTK_DIALOG(dialog)) == GTK_RESPONSE_ACCEPT)
        save->chosen = gtk_file_chooser_get_filename(GTK_FILE_CHOOSER(dialog));
    gtk_widget_destroy(dialog);
}

static const char* cn1WordWrite(const char* path, JAVA_OBJECT data) {
    FILE* f = fopen(path, "wb");
    size_t length = (size_t) (*(JAVA_ARRAY) data).length;
    if (f == NULL) return "The file could not be opened for writing";
    if (fwrite(CN1_ARRAY_DATA(data), 1, length, f) != length) {
        fclose(f);
        return "The file could not be written";
    }
    return fclose(f) == 0 ? NULL : "The file could not be written";
}

JAVA_OBJECT com_codenameone_wordprocessor_app_NativeFilesImplCodenameOne_saveAs___java_lang_String_java_lang_String_byte_1ARRAY_R_java_lang_String(CODENAME_ONE_THREAD_STATE, JAVA_OBJECT name, JAVA_OBJECT mime, JAVA_OBJECT data) {
    CN1WordSave save;
    const char* error;
    JAVA_OBJECT result;
    save.name = stringToUTF8(threadStateData, name);
    save.chosen = NULL;
    cn1LinuxRunOnMainAndWait(cn1WordSaveDialog, &save);
    if (save.chosen == NULL) return JAVA_NULL;
    error = cn1WordWrite(save.chosen, data);
    if (error != NULL) {
        char message[512];
        snprintf(message, sizeof(message), "error:%s", error);
        result = newStringFromCString(threadStateData, message);
    } else result = newStringFromCString(threadStateData, save.chosen);
    g_free(save.chosen);
    return result;
}

JAVA_OBJECT com_codenameone_wordprocessor_app_NativeFilesImplCodenameOne_writeFile___java_lang_String_byte_1ARRAY_R_java_lang_String(CODENAME_ONE_THREAD_STATE, JAVA_OBJECT location, JAVA_OBJECT data) {
    const char* error = cn1WordWrite(stringToUTF8(threadStateData, location), data);
    return error == NULL ? JAVA_NULL : newStringFromCString(threadStateData, error);
}

JAVA_BOOLEAN com_codenameone_wordprocessor_app_NativeFilesImplCodenameOne_isSupported___R_boolean(CODENAME_ONE_THREAD_STATE) {
    return cn1LinuxWindowWidget() != NULL ? JAVA_TRUE : JAVA_FALSE;
}
