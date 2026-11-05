import 'package:html/parser.dart' as html;

final class CatalogGenre {
  const CatalogGenre(this.id, this.name);
  final String id;
  final String name;
}

final class CatalogOptions {
  const CatalogOptions({required this.years, required this.genres});
  final List<int> years;
  final List<CatalogGenre> genres;

  factory CatalogOptions.fromHtml(String source) {
    final document = html.parse(source);
    final years =
        document
            .querySelectorAll('#dateissue option')
            .map((option) => int.tryParse(option.attributes['value'] ?? ''))
            .whereType<int>()
            .toSet()
            .toList()
          ..sort((a, b) => b.compareTo(a));
    final genres =
        document
            .querySelectorAll('#genres option')
            .where(
              (option) =>
                  (option.attributes['value'] ?? '').isNotEmpty &&
                  option.text.trim().isNotEmpty,
            )
            .map(
              (option) =>
                  CatalogGenre(option.attributes['value']!, option.text.trim()),
            )
            .toList()
          ..sort((a, b) => a.name.compareTo(b.name));
    if (years.isEmpty || genres.isEmpty) {
      throw const FormatException('На сайте не найдены фильтры каталога.');
    }
    return CatalogOptions(years: years, genres: genres);
  }
}
