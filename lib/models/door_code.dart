import 'package:uuid/uuid.dart';

class DoorCode {
  final String id;
  String code;
  String address;
  double latitude;
  double longitude;
  DateTime updatedAt;

  DoorCode({
    String? id,
    required this.code,
    this.address = '',
    required this.latitude,
    required this.longitude,
    DateTime? updatedAt,
  })  : id = id ?? const Uuid().v4(),
        updatedAt = updatedAt ?? DateTime.now();

  DoorCode copyWith({
    String? code,
    String? address,
    double? latitude,
    double? longitude,
  }) {
    return DoorCode(
      id: id,
      code: code ?? this.code,
      address: address ?? this.address,
      latitude: latitude ?? this.latitude,
      longitude: longitude ?? this.longitude,
      updatedAt: DateTime.now(),
    );
  }

  Map<String, dynamic> toJson() => {
        'id': id,
        'code': code,
        'address': address,
        'latitude': latitude,
        'longitude': longitude,
        'updatedAt': updatedAt.toIso8601String(),
      };

  factory DoorCode.fromJson(Map<String, dynamic> json) {
    return DoorCode(
      id: json['id'] as String,
      code: json['code'] as String,
      address: (json['address'] as String?) ?? '',
      latitude: (json['latitude'] as num).toDouble(),
      longitude: (json['longitude'] as num).toDouble(),
      updatedAt: json['updatedAt'] != null
          ? DateTime.parse(json['updatedAt'] as String)
          : DateTime.now(),
    );
  }

  @override
  String toString() =>
      'DoorCode($code @ $address, $latitude, $longitude)';
}