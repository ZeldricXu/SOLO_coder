import 'react-native-gesture-handler';
import React from 'react';
import { NavigationContainer } from '@react-navigation/native';
import { createBottomTabNavigator } from '@react-navigation/bottom-tabs';
import { createStackNavigator } from '@react-navigation/stack';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import Icon from 'react-native-vector-icons/MaterialIcons';

import AppManagementScreen from './screens/AppManagementScreen';
import AppFormScreen from './screens/AppFormScreen';
import AppDetailScreen from './screens/AppDetailScreen';
import VersionManagementScreen from './screens/VersionManagementScreen';
import VersionFormScreen from './screens/VersionFormScreen';
import FeedbackManagementScreen from './screens/FeedbackManagementScreen';
import FeedbackDetailScreen from './screens/FeedbackDetailScreen';
import ReportScreen from './screens/ReportScreen';
import { AppNavigatorParamList, RootTabParamList } from './navigation/types';

const Stack = createStackNavigator<AppNavigatorParamList>();
const Tab = createBottomTabNavigator<RootTabParamList>();

function AppStack() {
  return (
    <Stack.Navigator>
      <Stack.Screen 
        name="AppManagement" 
        component={AppManagementScreen}
        options={{ title: '应用管理' }}
      />
      <Stack.Screen 
        name="AppForm" 
        component={AppFormScreen}
        options={{ title: '创建应用' }}
      />
      <Stack.Screen 
        name="AppDetail" 
        component={AppDetailScreen}
        options={{ title: '应用详情' }}
      />
    </Stack.Navigator>
  );
}

function VersionStack() {
  return (
    <Stack.Navigator>
      <Stack.Screen 
        name="VersionManagement" 
        component={VersionManagementScreen}
        options={{ title: '版本发布' }}
      />
      <Stack.Screen 
        name="VersionForm" 
        component={VersionFormScreen}
        options={{ title: '提交版本' }}
      />
    </Stack.Navigator>
  );
}

function FeedbackStack() {
  return (
    <Stack.Navigator>
      <Stack.Screen 
        name="FeedbackManagement" 
        component={FeedbackManagementScreen}
        options={{ title: '反馈管理' }}
      />
      <Stack.Screen 
        name="FeedbackDetail" 
        component={FeedbackDetailScreen}
        options={{ title: '反馈详情' }}
      />
    </Stack.Navigator>
  );
}

function App() {
  return (
    <SafeAreaProvider>
      <NavigationContainer>
        <Tab.Navigator
          screenOptions={({ route }) => ({
            tabBarIcon: ({ focused, color, size }) => {
              let iconName = 'apps';
              if (route.name === 'Apps') iconName = 'apps';
              else if (route.name === 'Versions') iconName = 'update';
              else if (route.name === 'Feedback') iconName = 'feedback';
              else if (route.name === 'Reports') iconName = 'bar-chart';
              return <Icon name={iconName} size={size} color={color} />;
            },
            tabBarActiveTintColor: '#1976D2',
            tabBarInactiveTintColor: '#666',
          })}
        >
          <Tab.Screen 
            name="Apps" 
            component={AppStack}
            options={{ 
              tabBarLabel: '应用',
              headerShown: false 
            }}
          />
          <Tab.Screen 
            name="Versions" 
            component={VersionStack}
            options={{ 
              tabBarLabel: '版本',
              headerShown: false 
            }}
          />
          <Tab.Screen 
            name="Feedback" 
            component={FeedbackStack}
            options={{ 
              tabBarLabel: '反馈',
              headerShown: false 
            }}
          />
          <Tab.Screen 
            name="Reports" 
            component={ReportScreen}
            options={{ 
              tabBarLabel: '报表',
              headerShown: true,
              title: '数据报表'
            }}
          />
        </Tab.Navigator>
      </NavigationContainer>
    </SafeAreaProvider>
  );
}

export default App;
