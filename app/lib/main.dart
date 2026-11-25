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

  Future<void> setDoorbellEnabled(String cameraEntityId, bool enabled) async {
    try {
      final raw = await _channel.invokeMethod<Map<Object?, Object?>>('setDoorbellEnabled', {
        'cameraEntityId': cameraEntityId,
        'enabled': enabled,
      });
      if (raw != null) {
        status = AppStatus.fromMap(raw);
        notifyListeners();
      }
    } catch (_) {}
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

  Future<void> disconnect() async {
    final raw = await _channel.invokeMethod<Map<Object?, Object?>>('disconnect');
    if (raw != null) status = AppStatus.fromMap(raw);
    discovered = const [];
    await startDiscovery();
    notifyListeners();
  }

  Future<void> testCall() => _channel.invokeMethod('testCall');
  Future<String?> testRemotePush() async {
    try {
      await _channel.invokeMethod('testRemotePush');
      return null;
    } on PlatformException catch (e) {
      return e.message ?? 'Could not send the test call';
    }
  }
  Future<void> openUrl(String url) => _channel.invokeMethod('openUrl', {'url': url});
  Future<void> requestPermission(String type) =>
      _channel.invokeMethod('requestPermissions', {'type': type});
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
      seedColor: const Color(0xFF0277BD),
      brightness: brightness,
    );
    return ThemeData(
      useMaterial3: true,
      colorScheme: scheme,
      cardTheme: CardThemeData(
        elevation: 0,
        color: scheme.surfaceContainerLow,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
        margin: EdgeInsets.zero,
      ),
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
        appBar: AppBar(
          title: const Text(_appName),
          actions: [
            if (st.haConnected)
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
        ),
        body: SafeArea(
          child: st.haConnected
              ? _ConnectedBody(controller: widget.controller)
              : _OnboardingBody(
                  controller: widget.controller,
                  urlController: _urlController,
                ),
        ),
      );
    },
  );
}

class _SectionHeader extends StatelessWidget {
  const _SectionHeader(this.title, {this.padding = EdgeInsets.zero});

  final String title;
  final EdgeInsetsGeometry padding;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: padding,
      child: Text(
        title,
        style: theme.textTheme.labelLarge?.copyWith(color: theme.colorScheme.primary),
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
          if (i > 0) const Divider(height: 1, indent: 16, endIndent: 16),
          children[i],
        ],
      ],
    ),
  );
}

class _OnboardingBody extends StatelessWidget {
  const _OnboardingBody({
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
              const CircularProgressIndicator(),
              const SizedBox(height: 24),
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
      padding: const EdgeInsets.all(24),
      children: [
        const SizedBox(height: 16),
        CircleAvatar(
          radius: 36,
          backgroundColor: scheme.primaryContainer,
          child: Icon(Icons.doorbell_outlined, size: 36, color: scheme.onPrimaryContainer),
        ),
        const SizedBox(height: 20),
        Text(
          'Connect Home Assistant',
          style: theme.textTheme.headlineSmall,
          textAlign: TextAlign.center,
        ),
        const SizedBox(height: 8),
        Text(
          'Sign in once to receive instant incoming doorbell calls with live video.',
          style: theme.textTheme.bodyMedium?.copyWith(color: scheme.onSurfaceVariant),
          textAlign: TextAlign.center,
        ),
        if (controller.oauthError != null) ...[
          const SizedBox(height: 20),
          Card(
            color: scheme.errorContainer,
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Row(
                children: [
                  Icon(Icons.error_outline, color: scheme.onErrorContainer),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Text(
                      controller.oauthError!,
                      style: theme.textTheme.bodyMedium?.copyWith(color: scheme.onErrorContainer),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ],
        const SizedBox(height: 28),
        const _SectionHeader('Discovered on your network'),
        const SizedBox(height: 10),
        if (controller.discovered.isEmpty)
          const Card(
            child: ListTile(
              leading: SizedBox(
                width: 20,
                height: 20,
                child: CircularProgressIndicator(strokeWidth: 2.2),
              ),
              title: Text('Searching Wi-Fi for Home Assistant…'),
              subtitle: Text('Or enter your URL below if away from home'),
            ),
          )
        else
          ...controller.discovered.map(
            (inst) => Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: Card(
                clipBehavior: Clip.antiAlias,
                child: ListTile(
                  leading: CircleAvatar(
                    backgroundColor: scheme.secondaryContainer,
                    child: Icon(Icons.home_outlined, color: scheme.onSecondaryContainer),
                  ),
                  title: Text(inst.name),
                  subtitle: Text(
                    inst.version.isNotEmpty ? '${inst.url} • v${inst.version}' : inst.url,
                  ),
                  trailing: const Icon(Icons.arrow_forward),
                  onTap: () => controller.startOAuth(inst.url),
                ),
              ),
            ),
          ),
        const SizedBox(height: 24),
        const _SectionHeader('Manual URL'),
        const SizedBox(height: 10),
        TextField(
          controller: urlController,
          keyboardType: TextInputType.url,
          autocorrect: false,
          decoration: const InputDecoration(
            hintText: 'https://ha.example.com',
            prefixIcon: Icon(Icons.link),
            border: OutlineInputBorder(),
          ),
          onSubmitted: (v) {
            if (v.trim().isNotEmpty) controller.startOAuth(v);
          },
        ),
        const SizedBox(height: 12),
        FilledButton.icon(
          onPressed: () => controller.startOAuth(urlController.text),
          icon: const Icon(Icons.login),
          label: const Text('Sign in with browser'),
        ),
      ],
    );
  }
}

class _ConnectedBody extends StatelessWidget {
  const _ConnectedBody({required this.controller});

  final DoorbellCompanionController controller;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final st = controller.status;
    final total = st.doorbells.length;
    final active = st.enabledCount;

    final missingFullScreen = !st.fullScreenGranted;
    final missingNotif = !st.notificationsGranted;

    return ListView(
      padding: const EdgeInsets.all(24),
      children: [
        Card(
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Column(
              children: [
                CircleAvatar(
                  radius: 32,
                  backgroundColor: active > 0 ? scheme.primaryContainer : scheme.surfaceContainerHighest,
                  child: Icon(
                    active > 0 ? Icons.doorbell : Icons.notifications_off_outlined,
                    size: 32,
                    color: active > 0 ? scheme.onPrimaryContainer : scheme.onSurfaceVariant,
                  ),
                ),
                const SizedBox(height: 16),
                Text(
                  active > 0 ? 'Ready for doorbell calls' : 'Ring notifications paused',
                  style: theme.textTheme.titleLarge,
                  textAlign: TextAlign.center,
                ),
                const SizedBox(height: 6),
                Text(
                  total > 0
                      ? '$active of $total ${total == 1 ? 'doorbell' : 'doorbells'} active\n${st.haUrl}'
                      : st.haUrl,
                  style: theme.textTheme.bodyMedium?.copyWith(color: scheme.onSurfaceVariant),
                  textAlign: TextAlign.center,
                ),
              ],
            ),
          ),
        ),
        if (missingNotif || missingFullScreen) ...[
          const SizedBox(height: 16),
          Card(
            color: scheme.secondaryContainer,
            clipBehavior: Clip.antiAlias,
            child: ListTile(
              leading: Icon(Icons.warning_amber_rounded, color: scheme.onSecondaryContainer),
              title: Text(
                missingNotif ? 'Allow notifications' : 'Allow full-screen lockscreen calls',
                style: TextStyle(color: scheme.onSecondaryContainer),
              ),
              subtitle: Text(
                missingNotif
                    ? 'Required to receive incoming doorbell calls'
                    : 'Required to wake your screen when locked',
                style: TextStyle(color: scheme.onSecondaryContainer.withValues(alpha: 0.85)),
              ),
              trailing: const Icon(Icons.chevron_right),
              onTap: () => controller.requestPermission(
                missingNotif ? 'notifications' : 'fullScreen',
              ),
            ),
          ),
        ],
        if (st.pushReady == false) ...[
          const SizedBox(height: 16),
          Card(
            child: ListTile(
              leading: const Icon(Icons.phone_outlined),
              title: const Text('iPhone call registration pending'),
              subtitle: Text(
                st.pushError ?? 'Waiting for Apple push registration.',
              ),
            ),
          ),
        ],
        const SizedBox(height: 24),
        _SectionHeader(
          st.firstCameraName.isNotEmpty ? 'Diagnostics (${st.firstCameraName})' : 'Diagnostics',
        ),
        const SizedBox(height: 10),
        FilledButton.tonalIcon(
          onPressed: st.doorbells.isNotEmpty ? controller.testCall : null,
          icon: const Icon(Icons.videocam_outlined),
          label: const Text('Test call'),
        ),
        const SizedBox(height: 10),
        OutlinedButton.icon(
          onPressed: st.doorbells.isNotEmpty && st.pushReady != false
              ? () async {
                  ScaffoldMessenger.of(context).showSnackBar(
                    const SnackBar(
                      content: Text('Sending push via Home Assistant in 3s — lock your screen!'),
                      duration: Duration(seconds: 3),
                    ),
                  );
                  final error = await controller.testRemotePush();
                  if (error != null && context.mounted) {
                    ScaffoldMessenger.of(context).showSnackBar(
                      SnackBar(content: Text(error)),
                    );
                  }
                }
              : null,
          icon: const Icon(Icons.notifications_active_outlined),
          label: const Text('Test HA push (3s delay)'),
        ),
      ],
    );
  }
}

class SettingsScreen extends StatelessWidget {
  const SettingsScreen({super.key, required this.controller});

  final DoorbellCompanionController controller;

  Future<void> _confirmDisconnect(BuildContext context) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Disconnect Home Assistant?'),
        content: const Text(
          'This removes the Doorbell Companion doorbell automation from Home Assistant and signs out on this phone.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(ctx).pop(true),
            child: const Text('Disconnect'),
          ),
        ],
      ),
    );
    if (confirmed == true && context.mounted) {
      await controller.disconnect();
      if (context.mounted) Navigator.of(context).pop();
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
                      if (err != null && context.mounted) {
                        ScaffoldMessenger.of(context).showSnackBar(
                          SnackBar(content: Text(err)),
                        );
                      }
                    },
            ),
          ],
        ),
        body: SafeArea(
          top: false,
          child: ListView(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 32),
            children: [
              const _SectionHeader('Doorbells', padding: EdgeInsets.all(8)),
              if (st.doorbells.isEmpty)
                const Card(
                  child: ListTile(
                    title: Text('No doorbells found'),
                    subtitle: Text('Tap refresh to scan Home Assistant entities'),
                  ),
                )
              else
                _DividedCard(
                  children: [
                    for (final db in st.doorbells)
                      CheckboxListTile(
                        value: db.enabled,
                        onChanged: (val) => controller.setDoorbellEnabled(
                          db.cameraEntityId,
                          val ?? false,
                        ),
                        secondary: const Icon(Icons.doorbell_outlined),
                        title: Text(db.name),
                        subtitle: Text(
                          db.cameraEntityId,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                      ),
                  ],
                ),
              Padding(
                padding: const EdgeInsets.fromLTRB(8, 8, 8, 0),
                child: Text(
                  'Checked doorbells trigger incoming calls via Home Assistant.',
                  style: theme.textTheme.bodySmall?.copyWith(color: scheme.onSurfaceVariant),
                ),
              ),
              const SizedBox(height: 24),
              const _SectionHeader('Permissions', padding: EdgeInsets.fromLTRB(8, 0, 8, 8)),
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
                      icon: Icons.fullscreen,
                      title: 'Full-screen lockscreen calls',
                      granted: st.fullScreenGranted,
                      onTap: () => controller.requestPermission('fullScreen'),
                    ),
                  ],
                  _PermissionTile(
                    icon: Icons.mic_none_outlined,
                    title: 'Microphone (two-way audio)',
                    granted: st.microphoneGranted,
                    onTap: () => controller.requestPermission('microphone'),
                  ),
                ],
              ),
              const SizedBox(height: 24),
              const _SectionHeader('Home Assistant', padding: EdgeInsets.fromLTRB(8, 0, 8, 8)),
              _DividedCard(
                children: [
                  ListTile(
                    leading: const Icon(Icons.home_outlined),
                    title: const Text('Connected instance'),
                    subtitle: Text(st.haUrl),
                  ),
                  ListTile(
                    leading: const Icon(Icons.open_in_new),
                    title: const Text('Open Home Assistant settings'),
                    subtitle: const Text('my.home-assistant.io'),
                    onTap: () => controller.openUrl('https://my.home-assistant.io/redirect/config/'),
                  ),
                  ListTile(
                    leading: Icon(Icons.logout, color: scheme.error),
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
      subtitle: Text(granted ? 'Allowed' : 'Tap to allow'),
      trailing: Icon(
        granted ? Icons.check_circle : Icons.chevron_right,
        color: granted ? scheme.primary : scheme.error,
      ),
      onTap: granted ? null : onTap,
    );
  }
}
