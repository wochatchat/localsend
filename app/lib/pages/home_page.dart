import 'dart:io';

import 'package:desktop_drop/desktop_drop.dart';
import 'package:flutter/material.dart';
import 'package:localsend_app/config/init.dart';
import 'package:localsend_app/config/theme.dart';
import 'package:localsend_app/gen/strings.g.dart';
import 'package:localsend_app/pages/home_page_controller.dart';
import 'package:localsend_app/pages/tabs/receive_tab.dart';
import 'package:localsend_app/pages/tabs/send_tab.dart';
import 'package:localsend_app/pages/tabs/settings_tab.dart';
import 'package:localsend_app/provider/selection/selected_sending_files_provider.dart';
import 'package:localsend_app/provider/tv_provider.dart';
import 'package:localsend_app/util/native/cross_file_converters.dart';
import 'package:localsend_app/widget/responsive_builder.dart';
import 'package:refena_flutter/refena_flutter.dart';

enum HomeTab {
  receive(Icons.wifi),
  send(Icons.send),
  settings(Icons.settings)
  ;

  const HomeTab(this.icon);

  final IconData icon;

  String get label {
    switch (this) {
      case HomeTab.receive:
        return t.receiveTab.title;
      case HomeTab.send:
        return t.sendTab.title;
      case HomeTab.settings:
        return t.settingsTab.title;
    }
  }
}

class HomePage extends StatefulWidget {
  final HomeTab initialTab;

  /// It is important for the initializing step
  /// because the first init clears the cache
  final bool appStart;

  const HomePage({
    required this.initialTab,
    required this.appStart,
    super.key,
  });

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> with Refena {
  bool _dragAndDropIndicator = false;

  /// TV: per-tab focus anchors. The [PageView] keeps neighbor pages mounted,
  /// so the focus would otherwise stay on the invisible previous tab after a
  /// tab switch and D-pad navigation would move within that hidden page.
  final List<FocusNode> _tabFocusNodes = List.generate(HomeTab.values.length, (_) => FocusNode());
  HomeTab? _lastTvTab;

  @override
  void initState() {
    super.initState();
    // The controller already restored the last tab (see [HomePageController]).
    // Do not steal the focus on app start: ReceiveTab manages its own autofocus.
    _lastTvTab = widget.initialTab;
    ensureRef((ref) async {
      // On a plain app start the controller already restored the last tab from
      // persistence; only sync [widget.initialTab] for explicit re-entries
      // (e.g. tapping a transfer notification) that request another tab.
      if (!widget.appStart && ref.read(homePageControllerProvider).currentTab != widget.initialTab) {
        ref.redux(homePageControllerProvider).dispatch(ChangeTabAction(widget.initialTab));
      }
      await postInit(context, ref, widget.appStart);
    });
  }

  @override
  void dispose() {
    for (final node in _tabFocusNodes) {
      node.dispose();
    }
    super.dispose();
  }

  /// TV only: move the primary focus into the newly visible tab so that the
  /// remote's D-pad navigates the visible page instead of a hidden one.
  void _moveTvFocusIntoTab(HomeTab tab, BuildContext context) {
    if (_lastTvTab == tab) {
      return;
    }
    _lastTvTab = tab;
    if (!context.ref.read(tvProvider)) {
      return;
    }
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) {
        _tabFocusNodes[tab.index].requestFocus();
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    Translations.of(context); // rebuild on locale change
    final vm = context.watch(homePageControllerProvider);
    _moveTvFocusIntoTab(vm.currentTab, context);

    return DropTarget(
      onDragEntered: (_) {
        setState(() {
          _dragAndDropIndicator = true;
        });
      },
      onDragExited: (_) {
        setState(() {
          _dragAndDropIndicator = false;
        });
      },
      onDragDone: (event) async {
        // the drop may contain a mix of files and directories
        final droppedDirectories = event.files.where((file) => Directory(file.path).existsSync()).toList();
        final droppedFiles = event.files.where((file) => !Directory(file.path).existsSync()).toList();

        for (final directory in droppedDirectories) {
          await ref.redux(selectedSendingFilesProvider).dispatchAsync(AddDirectoryAction(directory.path));
        }

        if (droppedFiles.isNotEmpty) {
          await ref
              .redux(selectedSendingFilesProvider)
              .dispatchAsync(
                AddFilesAction(
                  files: droppedFiles,
                  converter: CrossFileConverters.convertXFile,
                ),
              );
        }
        vm.changeTab(HomeTab.send);
      },
      child: ResponsiveBuilder(
        builder: (sizingInformation) {
          return Scaffold(
            body: Row(
              children: [
                if (!sizingInformation.isMobile)
                  NavigationRail(
                    selectedIndex: vm.currentTab.index,
                    onDestinationSelected: (index) => vm.changeTab(HomeTab.values[index]),
                    extended: sizingInformation.isDesktop,
                    backgroundColor: Theme.of(context).cardColorWithElevation,
                    leading: sizingInformation.isDesktop
                        ? const Column(
                            children: [
                              SizedBox(height: 20),
                              Text(
                                'LocalSend',
                                style: TextStyle(fontSize: 32, fontWeight: FontWeight.bold),
                                textAlign: TextAlign.center,
                              ),
                              SizedBox(height: 20),
                            ],
                          )
                        : null,
                    destinations: HomeTab.values.map((tab) {
                      return NavigationRailDestination(
                        icon: Icon(tab.icon),
                        label: Text(tab.label),
                      );
                    }).toList(),
                  ),
                Expanded(
                  child: SafeArea(
                    left: sizingInformation.isMobile,
                    child: Stack(
                      children: [
                        PageView(
                          controller: vm.controller,
                          physics: const NeverScrollableScrollPhysics(),
                          children: [
                            for (final tab in HomeTab.values)
                              ExcludeFocus(
                                // Hidden PageView pages stay mounted: exclude them
                                // from focus traversal so the D-pad cannot wander
                                // into an invisible tab.
                                excluding: tab != vm.currentTab,
                                child: Focus(
                                  focusNode: _tabFocusNodes[tab.index],
                                  skipTraversal: true,
                                  includeSemantics: false,
                                  child: switch (tab) {
                                    HomeTab.receive => const ReceiveTab(),
                                    HomeTab.send => const SendTab(),
                                    HomeTab.settings => const SettingsTab(),
                                  },
                                ),
                              ),
                          ],
                        ),
                        if (_dragAndDropIndicator)
                          Container(
                            width: double.infinity,
                            decoration: BoxDecoration(
                              color: Theme.of(context).scaffoldBackgroundColor,
                            ),
                            child: Column(
                              mainAxisAlignment: MainAxisAlignment.center,
                              children: [
                                const Icon(Icons.file_download, size: 128),
                                const SizedBox(height: 30),
                                Text(t.sendTab.placeItems, style: Theme.of(context).textTheme.titleLarge),
                              ],
                            ),
                          ),
                      ],
                    ),
                  ),
                ),
              ],
            ),
            bottomNavigationBar: sizingInformation.isMobile
                ? NavigationBar(
                    selectedIndex: vm.currentTab.index,
                    onDestinationSelected: (index) => vm.changeTab(HomeTab.values[index]),
                    destinations: HomeTab.values.map((tab) {
                      return NavigationDestination(icon: Icon(tab.icon), label: tab.label);
                    }).toList(),
                  )
                : null,
          );
        },
      ),
    );
  }
}
