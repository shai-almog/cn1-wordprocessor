(function(exports){

var o = {};

    o.chooseSaveFile__java_lang_String = function(param1, callback) {
        callback.error(new Error("Not implemented yet"));
    };

    o.writeFile__java_lang_String_byte_1ARRAY = function(path, data, callback) {
        callback.error(new Error("Native file saving unavailable"));
    };

    o.isSupported_ = function(callback) {
        callback.complete(false);
    };

exports.com_codenameone_wordprocessor_app_NativeFiles= o;

})(cn1_get_native_interfaces());
