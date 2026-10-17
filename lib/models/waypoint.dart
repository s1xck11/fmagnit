import 'package:uuid/uuid.dart';

class Waypoint {
  final String id;
  String name;
  String group;
  double latitude;
  double longitude;
  double altitude;
  double accuracy;
  String doorCode;
  DateTime updatedAt;

  Waypoint({
    String? id,
    required this.name,
    this.group = '',
    required this.latitude,
    required this.longitude,
    this.altitude = 0.0,
    this.accuracy = 3.0,
    this.doorCode = '',
    DateTime? updatedAt,
  })  : id = id ?? const Uuid().v4(),
        updatedAt = updatedAt ?? DateTime.now();

  Waypoint copyWith({
    String? name,
    String? group,
    double? latitude,
    double? longitude,
    double? altitude,
    double? accuracy,
    String? doorCode,
  }) {
    return Waypoint(
      id: id,
      name: name ?? this.name,
      group: group ?? this.group,
      latitude: latitude ?? this.latitude,
      longitude: longitude ?? this.longitude,
      altitude: altitude ?? this.altitude,
      accuracy: accuracy ?? this.accuracy,
      doorCode: doorCode ?? this.doorCode,
      updatedAt: DateTime.now(),
    );
  }

  Map<String, dynamic> toJson() => {
        'id': id,
        'name': name,
        'group': group,
        'latitude': latitude,
        'longitude': longitude,
        'altitude': altitude,
        'accuracy': accuracy,
        if (doorCode.isNotEmpty) 'doorCode': doorCode,
        'updatedAt': updatedAt.toIso8601String(),
      };

  factory Waypoint.fromJson(Map<String, dynamic> json) {
    return Waypoint(
      id: json['id'] as String,
      name: json['name'] as String,
      group: (json['group'] as String?) ?? '',
      latitude: (json['latitude'] as num).toDouble(),
      longitude: (json['longitude'] as num).toDouble(),
      altitude: (json['altitude'] as num?)?.toDouble() ?? 0.0,
      accuracy: (json['accuracy'] as num?)?.toDouble() ?? 3.0,
      doorCode: (json['doorCode'] as String?) ?? '',
      updatedAt: json['updatedAt'] != null
          ? DateTime.parse(json['updatedAt'] as String)
          : DateTime.now(),
    );
  }

  @override
  String toString() =>
      'Waypoint($name @ $latitude, $longitude, group=$group, acc=$accuracy, door=$doorCode)';
}