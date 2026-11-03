import 'dart:convert';

/// Ignore a damaged local record without losing the other saved records.
T? readJsonRecord<T>(String raw, T? Function(Map<String, Object?>) decode) {
  try {
    final value = jsonDecode(raw);
    if (value is! Map) return null;
    return decode(value.cast<String, Object?>());
  } on FormatException {
    return null;
  } on TypeError {
    return null;
  } on ArgumentError {
    return null;
  }
}
