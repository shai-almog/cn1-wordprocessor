(function(exports){

// The File System Access Save dialog (Chromium browsers). Locations are "handle:N" keys for
// the chosen file handles, so Save rewrites the same file. Elsewhere isSupported() is false
// and the app offers a download instead.
var o = {};
var handles = [];

function types(name, mime) {
    var dot = name.lastIndexOf('.');
    if (dot < 0 || !mime) return undefined;
    var accept = {};
    accept[mime] = [name.substring(dot)];
    return [{ description: name.substring(dot + 1).toUpperCase() + ' file', accept: accept }];
}

function write(handle, data) {
    // Java bytes arrive as signed numbers; Uint8Array wraps them to 0..255.
    return handle.createWritable().then(function(stream) {
        return stream.write(new Uint8Array(data)).then(function() { return stream.close(); });
    });
}

o.saveAs__java_lang_String_java_lang_String_byte_1ARRAY = function(name, mime, data, callback) {
    window.showSaveFilePicker({ suggestedName: name, types: types(name, mime) }).then(function(handle) {
        return write(handle, data).then(function() {
            handles.push(handle);
            callback.complete('handle:' + (handles.length - 1));
        });
    }, function(e) {
        callback.complete(e && e.name === 'AbortError' ? null : 'error:' + e);
    }).catch(function(e) {
        callback.complete('error:' + e);
    });
};

o.writeFile__java_lang_String_byte_1ARRAY = function(location, data, callback) {
    var handle = handles[Number(String(location).substring(7))];
    if (!handle) {
        callback.complete('The file is no longer available');
        return;
    }
    write(handle, data).then(function() { callback.complete(null); },
            function(e) { callback.complete(String(e)); });
};

o.isSupported_ = function(callback) {
    callback.complete(typeof window.showSaveFilePicker === 'function');
};

exports.com_codenameone_wordprocessor_app_NativeFiles = o;

})(cn1_get_native_interfaces());
