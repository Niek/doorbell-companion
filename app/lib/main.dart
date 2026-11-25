import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

const _appName = 'Doorbell Companion';

void main() => runApp(const DoorbellCompanionApp());

class DoorbellItem {
  const DoorbellItem({
    required this.cameraEntityId,
    required this.name,
    required this.enabled,
  });

  final String cameraEntityId;
  final String name;
  final bool enabled;

  factory DoorbellItem.fromMap(Map<Object?, Object?> map) => DoorbellItem(
    cameraEntityId: (map['cameraEntityId'] as String?) ?? '',
    name: (map['name'] as String?) ?? 'Doorbell',
    enabled: map['enabled'] != false,
  );
}

class DiscoveredInstance {
  const DiscoveredInstance({
    required this.name,
    required this.url,
    required this.version,
  });

  final String name;
  final String url;
  final String version;

  factory DiscoveredInstance.fromMap(Map<Object?, Object?> map) => DiscoveredInstance(
    name: (map['name'] as String?) ?? 'Home Assistant',
    url: (map['url'] as String?) ?? '',
    version: (map['version'] as String?) ?? '',
  );
}

class AppStatus {
  const AppStatus({
    this.haConnected = false,
    this.haUrl = '',
    this.doorbells = const [],
    this.firstCameraName = '',
    this.notificationsGranted = true,
    this.microphoneGranted = false,
    this.fullScreenGranted = true,
    this.pushReady,
    this.pushError,
  });

  final bool haConnected;
  final String haUrl;
  final List<DoorbellItem> doorbells;
  final String firstCameraName;
  final bool notificationsGranted;
  final bool microphoneGranted;
  final bool fullScreenGranted;
  final bool? pushReady;
  final String? pushError;

  int get enabledCount => doorbells.where((d) => d.enabled).length;

  factory AppStatus.fromMap(Map<Object?, Object?> map) {
    final rawDoorbells = (map['doorbells'] as List<Object?>?) ?? const [];
    return AppStatus(
      haConnected: map['haConnected'] == true,
      haUrl: (map['haUrl'] as String?) ?? '',
      doorbells: rawDoorbells
          .whereType<Map<Object?, Object?>>()
          .map(DoorbellItem.fromMap)
          .toList(),
      firstCameraName: (map['firstCameraName'] as String?) ?? '',
      notificationsGranted: map['notificationsGranted'] != false,
      microphoneGranted: map['microphoneGranted'] == true,
      fullScreenGranted: map['fullScreenGranted'] != false,
      pushReady: map['pushReady'] as bool?,
      pushError: map['pushError'] as String?,
    );
  }
}

class DoorbellCompanionController extends ChangeNotifier {
  DoorbellCompanionController() {
    _channel.setMethodCallHandler(_handleNativeCall);
    refreshStatus();
  }

  static const _channel = MethodChannel('doorbell_companion');

  AppStatus status = const AppStatus();
  List<DiscoveredInstance> discovered = const [];
  String? oauthProgress;
  String? oauthError;
  bool refreshing = false;
  bool testPushPending = false;

  Future<dynamic> _handleNativeCall(MethodCall call) async {
    switch (call.method) {
      case 'onStatusChanged':
        final raw = call.arguments as Map<Object?, Object?>?;
        if (raw != null) {
          status = AppStatus.fromMap(raw);
          if (!status.haConnected) startDiscovery();
          notifyListeners();
        }
      case 'onDiscoveredInstances':
        final raw = call.arguments as List<Object?>?;
        if (raw != null) {
          discovered = raw
              .whereType<Map<Object?, Object?>>()
              .map(DiscoveredInstance.fromMap)
              .where((i) => i.url.isNotEmpty)
              .toList();
          notifyListeners();
        }
      case 'onOAuthProgress':
        oauthProgress = call.arguments as String?;
        oauthError = null;
        notifyListeners();
      case 'onOAuthComplete':
        oauthProgress = null;
        oauthError = null;
        final raw = call.arguments as Map<Object?, Object?>?;
        if (raw != null) status = AppStatus.fromMap(raw);
        notifyListeners();
      case 'onOAuthError':
        oauthProgress = null;
        oauthError = (call.arguments as String?) ?? 'Connection failed';
        startDiscovery();
        notifyListeners();
    }
  }

  Future<void> refreshStatus() async {
    try {
      final raw = await _channel.invokeMethod<Map<Object?, Object?>>('getStatus');
      if (raw != null) {
        status = AppStatus.fromMap(raw);
        if (!status.haConnected) startDiscovery();
        notifyListeners();
      }
    } catch (_) {}
  }

  Future<void> startDiscovery() async {
    try {
      await _channel.invokeMethod('startDiscovery');
    } catch (_) {}
  }

  Future<void> startOAuth(String url) async {
    url = url.trim();
    if (url.isEmpty) return;
    oauthError = null;
    oauthProgress = 'Continue in browser…';
    notifyListeners();
    try {
      await _channel.invokeMethod('startOAuth', {'url': url});
    } on PlatformException catch (e) {
      oauthProgress = null;
      oauthError = e.message ?? 'Invalid URL';
      notifyListeners();
    }
  }

  Future<void> cancelOAuth() async {
    oauthProgress = null;
    notifyListeners();
    try {
      await _channel.invokeMethod('cancelOAuth');
    } catch (_) {}
  }

  Future<String?> setDoorbellEnabled(String cameraEntityId, bool enabled) async {
    try {
      final raw = await _channel.invokeMethod<Map<Object?, Object?>>('setDoorbellEnabled', {
        'cameraEntityId': cameraEntityId,
        'enabled': enabled,
      });
      if (raw != null) {
        status = AppStatus.fromMap(raw);
        notifyListeners();
      }
      return null;
    } on PlatformException catch (e) {
      return e.message ?? 'Could not update the doorbell';
    }
  }

  Future<String?> refreshDoorbells() async {
    refreshing = true;
    notifyListeners();
    try {
      final raw = await _channel.invokeMethod<Map<Object?, Object?>>('refreshDoorbells');
      if (raw != null) status = AppStatus.fromMap(raw);
      return null;
    } on PlatformException catch (e) {
      return e.message ?? 'Could not refresh doorbells';
    } finally {
      refreshing = false;
      notifyListeners();
    }
  }

  Future<String?> disconnect() async {
    try {
      final raw = await _channel.invokeMethod<Map<Object?, Object?>>('disconnect');
      if (raw != null) status = AppStatus.fromMap(raw);
    } on PlatformException catch (e) {
      return e.message ?? 'Could not disconnect';
    }
    discovered = const [];
    oauthError = null;
    await startDiscovery();
    notifyListeners();
    return null;
  }

  Future<String?> testCall() async {
    try {
      await _channel.invokeMethod('testCall');
      return null;
    } on PlatformException catch (e) {
      return e.message ?? 'Could not start the test call';
    }
  }

  Future<String?> testRemotePush() async {
    if (testPushPending) return null;
    testPushPending = true;
    notifyListeners();
    try {
      await _channel.invokeMethod('testRemotePush');
      return null;
    } on PlatformException catch (e) {
      return e.message ?? 'Could not send the test call';
    } finally {
      testPushPending = false;
      notifyListeners();
    }
  }

  Future<void> openUrl(String url) async {
    try {
      await _channel.invokeMethod('openUrl', {'url': url});
    } catch (_) {}
  }

  Future<void> requestPermission(String type) async {
    try {
      await _channel.invokeMethod('requestPermissions', {'type': type});
    } catch (_) {}
  }
}


const _brandBlue = Color(0xFF2F6BFF);

/// Strips the scheme and trailing slash so URLs read as host names in the UI.
String _displayUrl(String url) =>
    url.replaceFirst(RegExp(r'^https?://'), '').replaceFirst(RegExp(r'/$'), '');

void _showMessage(BuildContext context, String message) {
  ScaffoldMessenger.of(context)
    ..hideCurrentSnackBar()
    ..showSnackBar(SnackBar(content: Text(message), behavior: SnackBarBehavior.floating));
}

class DoorbellCompanionApp extends StatefulWidget {
  const DoorbellCompanionApp({super.key});

  @override
  State<DoorbellCompanionApp> createState() => _DoorbellCompanionAppState();
}

class _DoorbellCompanionAppState extends State<DoorbellCompanionApp> {
  late final DoorbellCompanionController controller = DoorbellCompanionController();

  @override
  void dispose() {
    controller.dispose();
    super.dispose();
  }

  ThemeData _buildTheme(Brightness brightness) {
    final scheme = ColorScheme.fromSeed(
      seedColor: _brandBlue,
      brightness: brightness,
    );
    return ThemeData(
      useMaterial3: true,
      colorScheme: scheme,
      cardTheme: CardThemeData(
        elevation: 0,
        color: scheme.surfaceContainerLow,
        margin: EdgeInsets.zero,
      ),
      inputDecorationTheme: const InputDecorationTheme(border: OutlineInputBorder()),
    );
  }

  @override
  Widget build(BuildContext context) => MaterialApp(
    title: _appName,
    debugShowCheckedModeBanner: false,
    themeMode: ThemeMode.system,
    theme: _buildTheme(Brightness.light),
    darkTheme: _buildTheme(Brightness.dark),
    home: HomeScreen(controller: controller),
  );
}

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key, required this.controller});

  final DoorbellCompanionController controller;

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  final _urlController = TextEditingController();

  @override
  void dispose() {
    _urlController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: widget.controller,
    builder: (context, _) {
      final st = widget.controller.status;
      return Scaffold(
        appBar: st.haConnected
            ? AppBar(
                title: const Text(_appName),
                actions: [
                  IconButton(
                    icon: const Icon(Icons.settings_outlined),
                    tooltip: 'Settings',
                    onPressed: () => Navigator.of(context).push(
                      MaterialPageRoute<void>(
                        builder: (_) => SettingsScreen(controller: widget.controller),
                      ),
                    ),
                  ),
                ],
              )
            : null,
        body: SafeArea(
          child: AnimatedSwitcher(
            duration: const Duration(milliseconds: 250),
            child: st.haConnected
                ? _ConnectedBody(
                    key: const ValueKey('connected'),
                    controller: widget.controller,
                  )
                : _OnboardingBody(
                    key: const ValueKey('onboarding'),
                    controller: widget.controller,
                    urlController: _urlController,
                  ),
          ),
        ),
      );
    },
  );
}

class _AppLogo extends StatelessWidget {
  const _AppLogo({this.size = 88});

  final double size;

  @override
  Widget build(BuildContext context) =>
      Image.asset('assets/icon/app_logo.png', width: size, height: size);
}

class _SectionHeader extends StatelessWidget {
  const _SectionHeader(this.title);

  final String title;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.fromLTRB(4, 0, 4, 10),
      child: Text(
        title,
        style: theme.textTheme.titleSmall?.copyWith(
          color: theme.colorScheme.primary,
          fontWeight: FontWeight.w600,
        ),
      ),
    );
  }
}

class _DividedCard extends StatelessWidget {
  const _DividedCard({required this.children});

  final List<Widget> children;

  @override
  Widget build(BuildContext context) => Card(
    clipBehavior: Clip.antiAlias,
    child: Column(
      children: [
        for (int i = 0; i < children.length; i++) ...[
          if (i > 0) const Divider(height: 1, indent: 56),
          children[i],
        ],
      ],
    ),
  );
}

class _Banner extends StatelessWidget {
  const _Banner({
    required this.icon,
    required this.title,
    required this.message,
    this.error = false,
    this.onTap,
  });

  final IconData icon;
  final String title;
  final String message;
  final bool error;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final bg = error ? scheme.errorContainer : scheme.tertiaryContainer;
    final fg = error ? scheme.onErrorContainer : scheme.onTertiaryContainer;
    return Card(
      color: bg,
      clipBehavior: Clip.antiAlias,
      child: ListTile(
        leading: Icon(icon, color: fg),
        title: Text(title, style: TextStyle(color: fg, fontWeight: FontWeight.w600)),
        subtitle: Text(message, style: TextStyle(color: fg.withValues(alpha: 0.85))),
        trailing: onTap != null ? Icon(Icons.chevron_right, color: fg) : null,
        onTap: onTap,
      ),
    );
  }
}

class _OnboardingBody extends StatelessWidget {
  const _OnboardingBody({
    super.key,
    required this.controller,
    required this.urlController,
  });

  final DoorbellCompanionController controller;
  final TextEditingController urlController;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final progress = controller.oauthProgress;

    if (progress != null) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const _AppLogo(size: 72),
              const SizedBox(height: 32),
              const SizedBox(width: 160, child: LinearProgressIndicator()),
              const SizedBox(height: 20),
              Text(progress, style: theme.textTheme.titleMedium, textAlign: TextAlign.center),
              const SizedBox(height: 16),
              TextButton(
                onPressed: controller.cancelOAuth,
                child: const Text('Cancel'),
              ),
            ],
          ),
        ),
      );
    }

    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 32, 20, 32),
      children: [
        const Center(child: _AppLogo()),
        const SizedBox(height: 24),
        Text(
          _appName,
          style: theme.textTheme.headlineMedium?.copyWith(fontWeight: FontWeight.w600),
          textAlign: TextAlign.center,
        ),
        const SizedBox(height: 8),
        Text(
          'Connect Home Assistant to get incoming calls with live video when someone rings.',
          style: theme.textTheme.bodyLarge?.copyWith(color: scheme.onSurfaceVariant),
          textAlign: TextAlign.center,
        ),
        if (controller.oauthError != null) ...[
          const SizedBox(height: 24),
          _Banner(
            icon: Icons.error_outline,
            title: 'Could not connect',
            message: controller.oauthError!,
            error: true,
          ),
        ],
        const SizedBox(height: 32),
        const _SectionHeader('On your network'),
        if (controller.discovered.isEmpty)
          Card(
            child: ListTile(
              leading: const SizedBox(
                width: 24,
                height: 24,
                child: CircularProgressIndicator(strokeWidth: 2.2),
              ),
              title: const Text('Looking for Home Assistant…'),
              subtitle: Text(
                'Not at home? Enter your URL below.',
                style: TextStyle(color: scheme.onSurfaceVariant),
              ),
            ),
          )
        else
          _DividedCard(
            children: [
              for (final inst in controller.discovered)
                ListTile(
                  leading: const Icon(Icons.home_outlined),
                  title: Text(inst.name),
                  subtitle: Text(
                    inst.version.isNotEmpty
                        ? '${_displayUrl(inst.url)} · ${inst.version}'
                        : _displayUrl(inst.url),
                  ),
                  trailing: const Icon(Icons.chevron_right),
                  onTap: () => controller.startOAuth(inst.url),
                ),
            ],
          ),
        const SizedBox(height: 28),
        const _SectionHeader('Enter a URL'),
        TextField(
          controller: urlController,
          keyboardType: TextInputType.url,
          textInputAction: TextInputAction.go,
          autocorrect: false,
          enableSuggestions: false,
          decoration: const InputDecoration(
            hintText: 'https://ha.example.com',
            prefixIcon: Icon(Icons.link),
          ),
          onSubmitted: controller.startOAuth,
        ),
        const SizedBox(height: 12),
        ValueListenableBuilder<TextEditingValue>(
          valueListenable: urlController,
          builder: (context, value, _) => FilledButton.icon(
            onPressed: value.text.trim().isEmpty
                ? null
                : () => controller.startOAuth(value.text),
            icon: const Icon(Icons.login),
            label: const Text('Sign in with browser'),
          ),
        ),
      ],
    );
  }
}

class _StatusHero extends StatelessWidget {
  const _StatusHero({required this.status});

  final AppStatus status;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final total = status.doorbells.length;
    final active = status.enabledCount;
    final ready = active > 0;
    final String detail;
    if (total == 0) {
      detail = 'No doorbells found yet';
    } else if (ready) {
      detail = '$active of $total ${total == 1 ? 'doorbell' : 'doorbells'} will ring this phone';
    } else {
      detail = 'All doorbells are turned off in Settings';
    }
    final fg = ready ? scheme.onPrimaryContainer : scheme.onSurfaceVariant;

    return Card(
      color: ready ? scheme.primaryContainer : scheme.surfaceContainerHigh,
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(ready ? Icons.doorbell : Icons.notifications_paused_outlined, size: 36, color: fg),
            const SizedBox(height: 16),
            Text(
              ready ? 'Ready for doorbell calls' : 'Doorbell calls paused',
              style: theme.textTheme.titleLarge?.copyWith(color: fg),
            ),
            const SizedBox(height: 4),
            Text(detail, style: theme.textTheme.bodyMedium?.copyWith(color: fg)),
            const SizedBox(height: 4),
            Text(
              _displayUrl(status.haUrl),
              style: theme.textTheme.bodySmall?.copyWith(color: fg.withValues(alpha: 0.8)),
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
            ),
          ],
        ),
      ),
    );
  }
}

class _ConnectedBody extends StatelessWidget {
  const _ConnectedBody({super.key, required this.controller});

  final DoorbellCompanionController controller;

  Future<void> _refresh(BuildContext context) async {
    final err = await controller.refreshDoorbells();
    if (err != null && context.mounted) _showMessage(context, err);
  }

  @override
  Widget build(BuildContext context) {
    final st = controller.status;
    final hasDoorbell = st.doorbells.isNotEmpty;
    final cameraName = st.firstCameraName.isNotEmpty ? st.firstCameraName : 'your doorbell';

    return RefreshIndicator(
      onRefresh: () => _refresh(context),
      child: ListView(
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.fromLTRB(20, 8, 20, 32),
        children: [
          _StatusHero(status: st),
          if (!st.notificationsGranted) ...[
            const SizedBox(height: 12),
            _Banner(
              icon: Icons.notifications_off_outlined,
              title: 'Allow notifications',
              message: 'Needed to ring this phone when someone is at the door.',
              error: true,
              onTap: () => controller.requestPermission('notifications'),
            ),
          ],
          if (!st.fullScreenGranted) ...[
            const SizedBox(height: 12),
            _Banner(
              icon: Icons.screen_lock_portrait_outlined,
              title: 'Allow full-screen calls',
              message: 'Needed to show the call over your lock screen.',
              onTap: () => controller.requestPermission('fullScreen'),
            ),
          ],
          if (st.pushReady == false) ...[
            const SizedBox(height: 12),
            _Banner(
              icon: Icons.phone_iphone,
              title: st.pushError != null ? 'Call registration failed' : 'Registering for calls…',
              message: st.pushError ?? 'Waiting for Apple push registration.',
              error: st.pushError != null,
              onTap: st.pushError != null ? () => _refresh(context) : null,
            ),
          ],
          const SizedBox(height: 28),
          const _SectionHeader('Test'),
          _DividedCard(
            children: [
              ListTile(
                enabled: hasDoorbell,
                leading: const Icon(Icons.videocam_outlined),
                title: const Text('Test call'),
                subtitle: Text('Open the call screen for $cameraName'),
                trailing: const Icon(Icons.chevron_right),
                onTap: () async {
                  final err = await controller.testCall();
                  if (err != null && context.mounted) _showMessage(context, err);
                },
              ),
              ListTile(
                enabled: hasDoorbell && st.pushReady != false && !controller.testPushPending,
                leading: Icon(Icons.notifications_active_outlined),
                title: const Text('Test push'),
                subtitle: Text(
                  controller.testPushPending
                      ? 'Ringing in 3 seconds — lock your phone now'
                      : 'Rings through Home Assistant after 3 seconds',
                ),
                trailing: controller.testPushPending
                    ? const SizedBox(
                        width: 20,
                        height: 20,
                        child: CircularProgressIndicator(strokeWidth: 2.2),
                      )
                    : const Icon(Icons.chevron_right),
                onTap: () async {
                  final error = await controller.testRemotePush();
                  if (error != null && context.mounted) _showMessage(context, error);
                },
              ),
            ],
          ),
        ],
      ),
    );
  }
}

class SettingsScreen extends StatelessWidget {
  const SettingsScreen({super.key, required this.controller});

  final DoorbellCompanionController controller;

  Future<void> _confirmDisconnect(BuildContext context) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) {
        final scheme = Theme.of(ctx).colorScheme;
        return AlertDialog(
          icon: const Icon(Icons.logout),
          title: const Text('Disconnect Home Assistant?'),
          content: const Text(
            'This removes the Doorbell Companion automation from Home Assistant and signs out on this phone.',
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(ctx).pop(false),
              child: const Text('Cancel'),
            ),
            FilledButton(
              style: FilledButton.styleFrom(
                backgroundColor: scheme.error,
                foregroundColor: scheme.onError,
              ),
              onPressed: () => Navigator.of(ctx).pop(true),
              child: const Text('Disconnect'),
            ),
          ],
        );
      },
    );
    if (confirmed != true || !context.mounted) return;
    final err = await controller.disconnect();
    if (!context.mounted) return;
    if (err != null) {
      _showMessage(context, err);
    } else {
      Navigator.of(context).pop();
    }
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: controller,
    builder: (context, _) {
      final theme = Theme.of(context);
      final scheme = theme.colorScheme;
      final st = controller.status;

      return Scaffold(
        appBar: AppBar(
          title: const Text('Settings'),
          actions: [
            IconButton(
              icon: controller.refreshing
                  ? const SizedBox(
                      width: 20,
                      height: 20,
                      child: CircularProgressIndicator(strokeWidth: 2.2),
                    )
                  : const Icon(Icons.refresh),
              tooltip: 'Sync with Home Assistant',
              onPressed: controller.refreshing
                  ? null
                  : () async {
                      final err = await controller.refreshDoorbells();
                      if (!context.mounted) return;
                      _showMessage(context, err ?? 'Doorbells synced with Home Assistant');
                    },
            ),
          ],
        ),
        body: SafeArea(
          top: false,
          child: ListView(
            padding: const EdgeInsets.fromLTRB(20, 8, 20, 32),
            children: [
              const _SectionHeader('Doorbells'),
              if (st.doorbells.isEmpty)
                const Card(
                  child: ListTile(
                    leading: Icon(Icons.search_off),
                    title: Text('No doorbells found'),
                    subtitle: Text('Tap refresh to scan Home Assistant again'),
                  ),
                )
              else
                _DividedCard(
                  children: [
                    for (final db in st.doorbells)
                      SwitchListTile(
                        value: db.enabled,
                        onChanged: (val) async {
                          final err = await controller.setDoorbellEnabled(db.cameraEntityId, val);
                          if (err != null && context.mounted) _showMessage(context, err);
                        },
                        secondary: Icon(Icons.doorbell_outlined),
                        title: Text(db.name),
                        subtitle: Text(
                          db.cameraEntityId.replaceFirst('camera.', ''),
                          style: theme.textTheme.bodySmall?.copyWith(color: scheme.onSurfaceVariant),
                        ),
                      ),
                  ],
                ),
              Padding(
                padding: const EdgeInsets.fromLTRB(4, 8, 4, 0),
                child: Text(
                  'Doorbells that are on ring this phone through a Home Assistant automation.',
                  style: theme.textTheme.bodySmall?.copyWith(color: scheme.onSurfaceVariant),
                ),
              ),
              const SizedBox(height: 28),
              const _SectionHeader('Permissions'),
              _DividedCard(
                children: [
                  if (st.pushReady == null) ...[
                    _PermissionTile(
                      icon: Icons.notifications_outlined,
                      title: 'Notifications',
                      granted: st.notificationsGranted,
                      onTap: () => controller.requestPermission('notifications'),
                    ),
                    _PermissionTile(
                      icon: Icons.screen_lock_portrait_outlined,
                      title: 'Full-screen lock screen calls',
                      granted: st.fullScreenGranted,
                      onTap: () => controller.requestPermission('fullScreen'),
                    ),
                  ],
                  _PermissionTile(
                    icon: Icons.mic_none_outlined,
                    title: 'Microphone for talkback',
                    granted: st.microphoneGranted,
                    onTap: () => controller.requestPermission('microphone'),
                  ),
                ],
              ),
              const SizedBox(height: 28),
              const _SectionHeader('Home Assistant'),
              _DividedCard(
                children: [
                  ListTile(
                    leading: const Icon(Icons.home_outlined),
                    title: const Text('Connected to'),
                    subtitle: Text(_displayUrl(st.haUrl)),
                  ),
                  ListTile(
                    leading: const Icon(Icons.open_in_new),
                    title: const Text('Open Home Assistant'),
                    trailing: const Icon(Icons.chevron_right),
                    onTap: () => controller.openUrl('https://my.home-assistant.io/redirect/config/'),
                  ),
                  ListTile(
                    leading: Icon(Icons.logout),
                    title: Text('Disconnect', style: TextStyle(color: scheme.error)),
                    onTap: () => _confirmDisconnect(context),
                  ),
                ],
              ),
            ],
          ),
        ),
      );
    },
  );
}

class _PermissionTile extends StatelessWidget {
  const _PermissionTile({
    required this.icon,
    required this.title,
    required this.granted,
    required this.onTap,
  });

  final IconData icon;
  final String title;
  final bool granted;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return ListTile(
      leading: Icon(icon),
      title: Text(title),
      subtitle: Text(granted ? 'Allowed' : 'Not allowed · tap to allow'),
      trailing: granted
          ? Icon(Icons.check_circle, color: scheme.primary)
          : Icon(Icons.error_outline, color: scheme.error),
      onTap: granted ? null : onTap,
    );
  }
}
