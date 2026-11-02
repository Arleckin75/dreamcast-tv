import 'package:dream_cast/core/utils/json_record.dart';
import 'package:dream_cast/features/notifications/data/episode_notification_storage.dart';
import 'package:dream_cast/features/notifications/data/episode_notification_history_storage.dart';
import 'package:dream_cast/features/notifications/data/episode_notification_worker.dart';
import 'package:dream_cast/features/notifications/data/episode_notification_subscription.dart';
import 'package:dream_cast/features/releases/domain/release.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test(
    'invalid JSON and invalid field types do not break local reads',
    () async {
      SharedPreferences.setMockInitialValues({
        '${EpisodeNotificationStorage.itemPrefix}1': '{broken',
        '${EpisodeNotificationStorage.itemPrefix}2':
            '{"release":{"id":"invalid"}}',
        '${EpisodeNotificationHistoryStorage.itemPrefix}1': '[1,2]',
        '${EpisodeNotificationHistoryStorage.itemPrefix}2':
            '{"release":{"id":{}}}',
      });
      final preferences = await SharedPreferences.getInstance();
      expect(EpisodeNotificationStorage.readAll(preferences), isEmpty);
      expect(EpisodeNotificationHistoryStorage.readAll(preferences), isEmpty);
      expect(readJsonRecord<int>('{"id":3}', (value) => value['id'] as int), 3);
    },
  );

  test(
    'background checks rotate through more than forty subscriptions',
    () async {
      SharedPreferences.setMockInitialValues({});
      final preferences = await SharedPreferences.getInstance();
      final subscriptions = List.generate(
        45,
        (id) => EpisodeNotificationSubscription(
          release: DreamRelease(
            id: id,
            title: '$id',
            originalTitle: '',
            url: '/$id',
          ),
          lastKnownEpisodes: 1,
          updatedAt: DateTime.now(),
        ),
      );
      final first = await subscriptionsForNextCheck(preferences, subscriptions);
      final second = await subscriptionsForNextCheck(
        preferences,
        subscriptions.reversed.toList(),
      );
      expect(first.length, 40);
      expect(second.length, 40);
      expect(
        {...first, ...second}.map((item) => item.release.id).toSet().length,
        45,
      );
    },
  );
}
