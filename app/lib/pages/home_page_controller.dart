import 'package:flutter/material.dart';
import 'package:localsend_app/pages/home_page.dart';
import 'package:localsend_app/provider/persistence_provider.dart';
import 'package:refena_flutter/refena_flutter.dart';

class HomePageVm {
  final PageController controller;
  final HomeTab currentTab;
  final void Function(HomeTab) changeTab;

  HomePageVm({
    required this.controller,
    required this.currentTab,
    required this.changeTab,
  });
}

final homePageControllerProvider = ReduxProvider<HomePageController, HomePageVm>(
  (ref) => HomePageController(persistence: ref.read(persistenceProvider)),
);

class HomePageController extends ReduxNotifier<HomePageVm> {
  final PersistenceService _persistence;

  HomePageController({required PersistenceService persistence}) : _persistence = persistence;

  PersistenceService get persistence => _persistence;

  @override
  HomePageVm init() {
    final initialIndex = _persistence.getSelectedTab().clamp(0, HomeTab.values.length - 1);
    return HomePageVm(
      controller: PageController(initialPage: initialIndex),
      currentTab: HomeTab.values[initialIndex],
      changeTab: (tab) => redux.dispatch(ChangeTabAction(tab)),
    );
  }
}

class ChangeTabAction extends ReduxAction<HomePageController, HomePageVm> {
  final HomeTab tab;

  ChangeTabAction(this.tab);

  @override
  HomePageVm reduce() {
    state.controller.jumpToPage(tab.index);
    return HomePageVm(
      controller: state.controller,
      currentTab: tab,
      changeTab: state.changeTab,
    );
  }

  @override
  void after() {
    // Remember the last selected tab so that the app reopens on it.
    // ignore: discarded_futures
    notifier.persistence.setSelectedTab(tab.index);
  }
}
